use crate::consts::{APP_DATA_DIR, APP_PACKAGE_NAME, MAGISK_VER_CODE, TRUSTED_CERT};
use crate::daemon::{AID_APP_END, AID_APP_START, AID_USER_OFFSET, MagiskD, to_app_id};
use crate::ffi::{DbEntryKey, uninstall_pkg};
use base::WalkResult::{Abort, Continue, Skip};
use base::const_format::concatcp;
use base::{
    BufReadExt, Directory, FsPathBuilder, LoggedResult, ReadExt, ResultExt, Utf8CStrBuf,
    Utf8CString, cstr, error, fd_get_attr, pre_order_walk, warn,
};
use bit_set::BitSet;
use nix::fcntl::OFlag;
use std::collections::BTreeMap;
use std::fs::File;
use std::io;
use std::io::{Cursor, Read, Seek, SeekFrom};
use std::os::fd::AsRawFd;
use std::time::Duration;

const EOCD_MAGIC: u32 = 0x06054B50;
const APK_SIGNING_BLOCK_MAGIC: [u8; 16] = *b"APK Sig Block 42";
const SIGNATURE_SCHEME_V2_MAGIC: u32 = 0x7109871A;
const VERITY_PADDING_BLOCK_ID: u32 = 0x42726577;
const PACKAGES_XML: &str = "/data/system/packages.xml";

macro_rules! bad_apk {
    ($msg:literal) => {
        io::Error::new(io::ErrorKind::InvalidData, concat!("cert: ", $msg))
    };
}

/*
 * A v2/v3 signed APK has the format as following
 *
 * +---------------+
 * | zip content   |
 * +---------------+
 * | signing block |
 * +---------------+
 * | central dir   |
 * +---------------+
 * | EOCD          |
 * +---------------+
 *
 * Scan from end of file to find EOCD, and figure our way back to the
 * offset of the signing block. Next, directly extract the certificate
 * from the v2 signature block.
 *
 * All structures above are mostly just for documentation purpose.
 *
 * This method extracts the first certificate of the first signer
 * within the APK v2 signature block.
 */
fn read_certificate(apk: &mut File, version: i32) -> Vec<u8> {
    let res = || -> io::Result<Vec<u8>> {
        let mut u32_val = 0u32;
        let mut u64_val = 0u64;

        // Find EOCD
        for i in 0u16.. {
            let mut comment_sz = 0u16;
            apk.seek(SeekFrom::End(-(size_of_val(&comment_sz) as i64) - i as i64))?;
            apk.read_pod(&mut comment_sz)?;

            if comment_sz == i {
                apk.seek(SeekFrom::Current(-22))?;
                let mut magic = 0u32;
                apk.read_pod(&mut magic)?;
                if magic == EOCD_MAGIC {
                    break;
                }
            }
            if i == 0xffff {
                Err(bad_apk!("invalid APK format"))?;
            }
        }

        // We are now at EOCD + sizeof(magic)
        // Seek and read central_dir_off to find the start of the central directory
        let mut central_dir_off = 0u32;
        apk.seek(SeekFrom::Current(12))?;
        apk.read_pod(&mut central_dir_off)?;

        // Code for parse APK comment to get version code
        // (a negative version means the version check is skipped, e.g. repackaged stub APKs)
        if version >= 0 {
            let mut comment_sz = 0u16;
            apk.read_pod(&mut comment_sz)?;
            let mut comment = vec![0u8; comment_sz as usize];
            apk.read_exact(&mut comment)?;
            let mut comment = Cursor::new(&comment);
            let mut apk_ver = 0;
            comment.for_each_prop(|k, v| {
                if k == "versionCode" {
                    apk_ver = v.parse::<i32>().unwrap_or(0);
                    false
                } else {
                    true
                }
            });
            if version > apk_ver {
                Err(bad_apk!("APK version too low"))?;
            }
        }

        // Next, find the start of the APK signing block
        let seq_end = apk.seek(SeekFrom::Start((central_dir_off - 24) as u64))?;
        apk.read_pod(&mut u64_val)?; // u64_value = block_sz_
        let mut magic = [0u8; 16];
        apk.read_exact(&mut magic)?;
        if magic != APK_SIGNING_BLOCK_MAGIC {
            Err(bad_apk!("invalid signing block magic"))?;
        }
        let mut signing_blk_sz = 0u64;
        let mut cur = apk.seek(SeekFrom::Current(
            -(u64_val as i64) - (size_of_val(&signing_blk_sz) as i64),
        ))?;
        apk.read_pod(&mut signing_blk_sz)?;
        cur += size_of_val(&signing_blk_sz) as u64;
        if signing_blk_sz != u64_val {
            Err(bad_apk!("invalid signing block size"))?;
        }

        // Finally, we are now at the beginning of the id-value pair sequence
        let mut v2_cert: Option<Vec<u8>> = None;
        loop {
            if cur == seq_end {
                break match v2_cert {
                    Some(cert) => Ok(cert),
                    None => Err(bad_apk!("cannot find certificate")),
                };
            }

            if cur > seq_end || seq_end - cur < 12 {
                Err(bad_apk!("truncated id-value pair"))?;
            }

            apk.read_pod(&mut u64_val)?; // id-value pair length
            let pair_end = cur + (size_of_val(&u64_val) as u64) + u64_val;
            if u64_val < 4 || pair_end > seq_end {
                Err(bad_apk!("invalid id-value pair length"))?;
            }

            let mut id = 0u32;
            apk.read_pod(&mut id)?;
            if id == SIGNATURE_SCHEME_V2_MAGIC {
                if v2_cert.is_some() {
                    Err(bad_apk!("duplicate signature scheme v2 block"))?;
                }

                // Skip [signer sequence length] + [1st signer length] + [signed data length]
                apk.seek(SeekFrom::Current((size_of_val(&u32_val) * 3) as i64))?;

                apk.read_pod(&mut u32_val)?; // digest sequence length
                apk.seek(SeekFrom::Current(u32_val as i64))?; // skip all digests

                apk.seek(SeekFrom::Current(size_of_val(&u32_val) as i64))?; // cert sequence length
                apk.read_pod(&mut u32_val)?; // 1st cert length

                let here = apk.stream_position()?;
                if here + u32_val as u64 > pair_end {
                    Err(bad_apk!("invalid certificate length"))?;
                }
                let mut cert = vec![0; u32_val as usize];
                apk.read_exact(cert.as_mut())?;
                v2_cert = Some(cert);
            } else if id == VERITY_PADDING_BLOCK_ID {
                // Skip this id-value pair
            } else {
                Err(bad_apk!("unknown block id"))?;
            }

            cur = apk.seek(SeekFrom::Start(pair_end))?;
        }
    }();
    res.log().unwrap_or(vec![])
}

fn find_apk_path(pkg: &str) -> LoggedResult<Utf8CString> {
    let mut buf = Utf8CString::default();
    pre_order_walk(cstr!("/data/app"), |e, path| {
        if !e.is_dir() {
            return Ok(Skip);
        }
        let name_bytes = e.name().as_bytes();
        if name_bytes.starts_with(pkg.as_bytes()) && name_bytes.get(pkg.len()) == Some(&b'-') {
            // Found the APK path, we can abort now
            buf.append_path(path).append_path("base.apk");
            return Ok(Abort);
        }
        if name_bytes.starts_with(b"~~") {
            return Ok(Continue);
        }
        Ok(Skip)
    })?;
    Ok(buf)
}

fn get_package_uid(user: i32, pkg: &str) -> i32 {
    let path = cstr::buf::default()
        .join_path(APP_DATA_DIR)
        .join_path_fmt(user)
        .join_path(pkg);
    path.get_attr()
        .map(|attr| attr.st.st_uid as i32)
        .unwrap_or(-1)
}

/// The certificate of the original (non-hidden) app is embedded at build time.
/// When no key store was configured the embedded certificate is empty and any
/// validly signed APK is accepted (development builds).
fn is_trusted_cert(cert: &[u8]) -> bool {
    !cert.is_empty() && (TRUSTED_CERT.is_empty() || cert == TRUSTED_CERT)
}

enum Status {
    Installed,
    NotInstalled,
    CertMismatch,
}

#[derive(Default)]
struct TrackedFile {
    path: Utf8CString,
    timestamp: Duration,
}

impl TrackedFile {
    fn new(path: Utf8CString) -> TrackedFile {
        let attr = match path.get_attr() {
            Ok(attr) => attr,
            Err(_) => return TrackedFile::default(),
        };
        let timestamp = Duration::new(attr.st.st_ctime as u64, attr.st.st_ctime_nsec as u32);
        TrackedFile { path, timestamp }
    }

    fn is_same(&self) -> bool {
        if self.path.is_empty() {
            return false;
        }
        let attr = match self.path.get_attr() {
            Ok(attr) => attr,
            Err(_) => return false,
        };
        let timestamp = Duration::new(attr.st.st_ctime as u64, attr.st.st_ctime_nsec as u32);
        timestamp == self.timestamp
    }
}

/// Tracks the manager app. The manager is either the original app
/// ([APP_PACKAGE_NAME]) or a hidden (repackaged) app: a stub APK with a random
/// package name stored in the database (key `requester`), which loads the real
/// app from its data directory (`dyn/current.apk`).
///
/// - The original app and the dyn APK must be signed with the trusted certificate
///   that was embedded at build time.
/// - The stub APK is signed with a randomly generated key, so it is trust on
///   first use: once seen, its certificate has to stay the same.
pub struct ManagerInfo {
    repackaged_app_id: i32,
    repackaged_pkg: String,
    repackaged_cert: Vec<u8>,
    tracked_files: BTreeMap<i32, TrackedFile>,
}

impl Default for ManagerInfo {
    fn default() -> Self {
        ManagerInfo {
            repackaged_app_id: -1,
            repackaged_pkg: String::new(),
            repackaged_cert: Vec::new(),
            tracked_files: BTreeMap::new(),
        }
    }
}

impl ManagerInfo {
    fn check_dyn(&mut self, user: i32, pkg: &str) -> Status {
        let apk = cstr::buf::default()
            .join_path(APP_DATA_DIR)
            .join_path_fmt(user)
            .join_path(pkg)
            .join_path("dyn")
            .join_path("current.apk");
        let uid: i32;
        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => {
                uid = fd_get_attr(fd.as_raw_fd())
                    .map(|attr| attr.st.st_uid as i32)
                    .unwrap_or(-1);
                read_certificate(&mut fd, MAGISK_VER_CODE)
            }
            Err(_) => {
                warn!("pkg: no dyn APK, ignore");
                return Status::NotInstalled;
            }
        };

        if !is_trusted_cert(&cert) {
            error!("pkg: dyn APK signature mismatch: {}", apk);
            #[cfg(all(feature = "check-signature", not(debug_assertions)))]
            {
                return Status::CertMismatch;
            }
        }

        self.repackaged_app_id = to_app_id(uid);
        self.tracked_files
            .insert(user, TrackedFile::new(apk.to_owned()));
        Status::Installed
    }

    fn check_stub(&mut self, user: i32, pkg: &str) -> Status {
        let Ok(apk) = find_apk_path(pkg) else {
            return Status::NotInstalled;
        };

        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => read_certificate(&mut fd, -1),
            Err(_) => return Status::NotInstalled,
        };

        if cert.is_empty() || (pkg == self.repackaged_pkg && cert != self.repackaged_cert) {
            error!("pkg: repackaged APK signature invalid: {}", apk);
            uninstall_pkg(&Utf8CString::from(pkg));
            return Status::CertMismatch;
        }

        self.repackaged_pkg.clear();
        self.repackaged_pkg.push_str(pkg);
        self.repackaged_cert = cert;
        self.tracked_files.insert(user, TrackedFile::new(apk));
        Status::Installed
    }

    fn check_orig(&mut self, user: i32) -> Status {
        let Ok(apk) = find_apk_path(APP_PACKAGE_NAME) else {
            return Status::NotInstalled;
        };

        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => read_certificate(&mut fd, MAGISK_VER_CODE),
            Err(_) => return Status::NotInstalled,
        };

        if !is_trusted_cert(&cert) {
            error!("pkg: APK signature mismatch: {}", apk);
            #[cfg(all(feature = "check-signature", not(debug_assertions)))]
            {
                uninstall_pkg(cstr!(APP_PACKAGE_NAME));
                return Status::CertMismatch;
            }
        }

        self.tracked_files.insert(user, TrackedFile::new(apk));
        Status::Installed
    }

    fn none() -> (i32, String) {
        (-1, String::new())
    }

    fn get_manager(&mut self, daemon: &MagiskD, user: i32) -> (i32, String) {
        let db_pkg = daemon.get_db_string(DbEntryKey::SuManager);

        // If database changed, always re-check files
        if db_pkg != self.repackaged_pkg {
            self.tracked_files.remove(&user);
        }

        // Figure out what the previously tracked file (if still unchanged) was
        let tracked = self.tracked_files.get(&user).and_then(|file| {
            file.is_same().then(|| {
                (
                    &file.path == PACKAGES_XML,
                    file.path.starts_with(APP_DATA_DIR),
                )
            })
        });

        if let Some((is_packages_xml, is_dyn)) = tracked {
            // no APK
            if is_packages_xml {
                return Self::none();
            }
            // dyn APK is still the same
            if is_dyn {
                return (
                    user * AID_USER_OFFSET + self.repackaged_app_id,
                    self.repackaged_pkg.clone(),
                );
            }
            // stub APK is still the same
            if !self.repackaged_pkg.is_empty() {
                let pkg = self.repackaged_pkg.clone();
                return if matches!(self.check_dyn(user, &pkg), Status::Installed) {
                    (user * AID_USER_OFFSET + self.repackaged_app_id, pkg)
                } else {
                    Self::none()
                };
            }
            // orig APK is still the same
            let uid = get_package_uid(user, APP_PACKAGE_NAME);
            return if uid < 0 {
                Self::none()
            } else {
                (uid, APP_PACKAGE_NAME.to_string())
            };
        }

        if !db_pkg.is_empty() {
            match self.check_stub(user, &db_pkg) {
                Status::Installed => {
                    return if matches!(self.check_dyn(user, &db_pkg), Status::Installed) {
                        (
                            user * AID_USER_OFFSET + self.repackaged_app_id,
                            self.repackaged_pkg.clone(),
                        )
                    } else {
                        Self::none()
                    };
                }
                Status::NotInstalled | Status::CertMismatch => {
                    daemon.rm_db_string(DbEntryKey::SuManager).ok();
                }
            }
        }

        self.repackaged_pkg.clear();
        self.repackaged_cert.clear();

        if matches!(self.check_orig(user), Status::Installed) {
            let uid = get_package_uid(user, APP_PACKAGE_NAME);
            return if uid < 0 {
                Self::none()
            } else {
                (uid, APP_PACKAGE_NAME.to_string())
            };
        }

        // If we cannot find any manager, track packages.xml for new package installs
        self.tracked_files
            .insert(user, TrackedFile::new(PACKAGES_XML.into()));
        Self::none()
    }
}

impl MagiskD {
    /// Returns (uid, package name) of the manager for the user.
    /// The package name is empty when there is no manager.
    pub fn get_manager(&self, user: i32) -> (i32, String) {
        let mut info = self.manager_info.lock();
        info.get_manager(self, user)
    }

    pub fn get_manager_uid(&self, user: i32) -> i32 {
        self.get_manager(user).0
    }

    // app_id = app_no + AID_APP_START
    // app_no range: [0, 9999]
    pub fn get_app_no_list(&self) -> BitSet {
        let mut list = BitSet::new();
        let _ = || -> LoggedResult<()> {
            let mut app_data_dir = Directory::open(cstr!(APP_DATA_DIR))?;
            // For each user
            loop {
                let entry = match app_data_dir.read()? {
                    None => break,
                    Some(e) => e,
                };
                let mut user_dir = match entry.open_as_dir() {
                    Err(_) => continue,
                    Ok(dir) => dir,
                };
                // For each package
                while let Some(e) = user_dir.read()? {
                    let st = e.get_stat()?;
                    let app_id = to_app_id(st.st_uid as i32);
                    if (AID_APP_START..=AID_APP_END).contains(&app_id) {
                        let app_no = app_id - AID_APP_START;
                        list.insert(app_no as usize);
                    }
                }
            }
            Ok(())
        }();
        list
    }

    pub fn get_users(&self) -> Vec<i32> {
        let mut users = Vec::new();
        let _ = || -> LoggedResult<()> {
            let mut app_data_dir = Directory::open(cstr!(APP_DATA_DIR))?;
            while let Some(e) = app_data_dir.read()? {
                if e.is_dir()
                    && let Ok(user) = e.name().parse::<i32>()
                {
                    if self.is_headless && user == 0 {
                        continue;
                    }
                    if user >= 0 {
                        users.push(user);
                    }
                }
            }
            Ok(())
        }();
        if users.is_empty() {
            users.push(0);
        }
        users.sort_unstable();
        users.dedup();
        users
    }

    pub fn get_app_id(&self, pkg: &str) -> i32 {
        for user in self.get_users() {
            let uid = get_package_uid(user, pkg);
            if uid >= 0 {
                return to_app_id(uid);
            }
        }
        0
    }
}


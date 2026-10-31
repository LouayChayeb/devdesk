//! The desktop shell. It only starts the DevDesk engine (`devdeskd`), tells the UI where it listens,
//! and keeps the engine's stdin open. When this app exits (or crashes), the OS closes that pipe and the engine
//! stops every service (closing DevDesk stops everything). No other logic lives here.

use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{Manager, RunEvent, State};

/// Where the engine listens, and the secret it expects. Given to the UI only.
#[derive(Clone, Serialize, Deserialize)]
struct Connection {
    port: u16,
    token: String,
}

#[derive(Default)]
struct Engine {
    child: Option<Child>,
    connection: Option<Connection>,
}

/// `DEVDESK_ENGINE` overrides the path. An installed app (.deb, AppImage) uses the engine bundled with it, which
/// brings its own Java; during development it's the one built by `./gradlew :daemon:installDist`.
fn engine_path(app: &tauri::AppHandle) -> String {
    if let Ok(path) = std::env::var("DEVDESK_ENGINE") {
        return path;
    }
    if let Ok(dir) = app.path().resource_dir() {
        let bundled = dir.join("engine").join("devdeskd");
        if bundled.is_file() {
            return bundled.to_string_lossy().into_owned();
        }
    }
    concat!(env!("CARGO_MANIFEST_DIR"), "/../../daemon/build/install/devdeskd/bin/devdeskd").to_string()
}

/// Variables that only exist because the app was started through `npm run app` → tauri → cargo. Services must get
/// the user's normal shell environment, as with `devdesk up` in a terminal: `npm_config_*` alone can change how an
/// `npm run ...` service behaves.
fn is_tooling_variable(key: &str) -> bool {
    const PREFIXES: [&str; 5] = ["npm_", "CARGO", "TAURI_", "RUSTUP_", "RUST_"];
    const EXACT: [&str; 6] = ["INIT_CWD", "OUT_DIR", "NODE", "COLOR", "NODE_ENV", "WEBKIT_DISABLE_DMABUF_RENDERER"];
    PREFIXES.iter().any(|p| key.starts_with(p)) || EXACT.contains(&key)
}

/// PATH and LD_LIBRARY_PATH entries added by npm (node_modules/.bin) and cargo (target/, the toolchain).
fn is_tooling_path(entry: &str) -> bool {
    entry.contains("/node_modules/.bin")
        || entry.contains("node-gyp-bin")
        || entry.contains("/.rustup/toolchains/")
        || entry.contains("/src-tauri/target/")
}

/// This app's environment without the developer tools' own variables and paths, for the engine and every service.
fn clean_environment() -> Vec<(String, String)> {
    std::env::vars()
        .filter(|(k, _)| !is_tooling_variable(k))
        .filter_map(|(k, v)| {
            if k == "PATH" || k == "LD_LIBRARY_PATH" {
                let kept: Vec<&str> = v.split(':').filter(|e| !e.is_empty() && !is_tooling_path(e)).collect();
                if kept.is_empty() {
                    return None;
                }
                return Some((k, kept.join(":")));
            }
            Some((k, v))
        })
        .collect()
}

/// Starts `devdeskd` and reads its first stdout line, `{"port":...,"token":...}`. Fails with a plain message
/// when the engine is missing or exits before saying where it listens.
fn start_engine(app: &tauri::AppHandle) -> Result<(Child, Connection), String> {
    let path = engine_path(app);
    let mut child = Command::new(&path)
        .env_clear()
        .envs(clean_environment())
        .stdin(Stdio::piped()) // the lifeline: kept open for as long as this app lives
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("could not start the DevDesk engine at {path}: {e}. Build it with: ./gradlew :daemon:installDist"))?;
    let mut lines = BufReader::new(child.stdout.take().expect("stdout is piped")).lines();
    let first = lines
        .next()
        .ok_or("the engine exited before saying where it listens (see the terminal for its error)")?
        .map_err(|e| format!("could not read from the engine: {e}"))?;
    let connection: Connection = serde_json::from_str(&first)
        .map_err(|e| format!("the engine printed {first:?} instead of its address: {e}"))?;
    // Keep reading its stdout so the pipe never fills up and blocks the engine.
    std::thread::spawn(move || lines.for_each(|_| {}));
    Ok((child, connection))
}

/// Starts the engine on first use (or again if it died) and returns where to reach it.
#[tauri::command]
fn engine_connection(app: tauri::AppHandle, state: State<Mutex<Engine>>) -> Result<Connection, String> {
    let mut engine = state.lock().map_err(|_| "engine state poisoned")?;
    if let Some(child) = engine.child.as_mut() {
        if let Ok(Some(_)) = child.try_wait() {
            engine.child = None; // it exited: start a fresh one
            engine.connection = None;
        }
    }
    if engine.connection.is_none() {
        let (child, connection) = start_engine(&app)?;
        engine.child = Some(child);
        engine.connection = Some(connection);
    }
    Ok(engine.connection.clone().expect("set above"))
}

/// Runs the desktop app. On exit it closes the engine's stdin and waits up to 60 s for it to stop every service.
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        // Desktop pop-ups when a service crashes while DevDesk's window isn't in front.
        .plugin(tauri_plugin_notification::init())
        .manage(Mutex::new(Engine::default()))
        .invoke_handler(tauri::generate_handler![engine_connection])
        .build(tauri::generate_context!())
        .expect("error while building the DevDesk app");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            // Close the engine's stdin so it stops every service now, and give it time to do so in order.
            if let Ok(mut engine) = handle.state::<Mutex<Engine>>().lock() {
                if let Some(mut child) = engine.child.take() {
                    drop(child.stdin.take());
                    let deadline = Instant::now() + Duration::from_secs(60);
                    while Instant::now() < deadline {
                        if let Ok(Some(_)) = child.try_wait() {
                            break;
                        }
                        std::thread::sleep(Duration::from_millis(100));
                    }
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dev_tooling_variables_are_dropped_and_user_ones_kept() {
        for k in ["npm_config_cache", "CARGO_PKG_NAME", "TAURI_CLI_VERBOSITY", "RUSTUP_TOOLCHAIN", "INIT_CWD", "OUT_DIR"] {
            assert!(is_tooling_variable(k), "{k} should be dropped");
        }
        for k in ["HOME", "JAVA_HOME", "NVM_DIR", "EDITOR", "DATABASE_URL", "XDG_RUNTIME_DIR"] {
            assert!(!is_tooling_variable(k), "{k} should be kept");
        }
    }

    #[test]
    fn npm_and_cargo_path_entries_are_dropped() {
        assert!(is_tooling_path("/home/me/code/devdesk/ui/node_modules/.bin"));
        assert!(is_tooling_path("/home/me/.rustup/toolchains/stable-x86_64-unknown-linux-gnu/lib"));
        assert!(!is_tooling_path("/home/me/.nvm/versions/node/v24.21.0/bin"));
        assert!(!is_tooling_path("/usr/bin"));
    }
}

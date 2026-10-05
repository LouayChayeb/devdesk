//! The desktop shell. It only starts the DevDesk engine (`devdeskd`), tells the UI where it listens,
//! and keeps the engine's stdin open. When this app exits (or crashes), the OS closes that pipe and the engine
//! stops every service (ADR 0005: closing DevDesk stops everything). No other logic lives here.

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

/// `DEVDESK_ENGINE` overrides the path; by default the engine built by `./gradlew :daemon:installDist`.
fn engine_path() -> String {
    std::env::var("DEVDESK_ENGINE").unwrap_or_else(|_| {
        concat!(env!("CARGO_MANIFEST_DIR"), "/../../daemon/build/install/devdeskd/bin/devdeskd").to_string()
    })
}

fn start_engine() -> Result<(Child, Connection), String> {
    let path = engine_path();
    let mut child = Command::new(&path)
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
fn engine_connection(state: State<Mutex<Engine>>) -> Result<Connection, String> {
    let mut engine = state.lock().map_err(|_| "engine state poisoned")?;
    if let Some(child) = engine.child.as_mut() {
        if let Ok(Some(_)) = child.try_wait() {
            engine.child = None; // it exited: start a fresh one
            engine.connection = None;
        }
    }
    if engine.connection.is_none() {
        let (child, connection) = start_engine()?;
        engine.child = Some(child);
        engine.connection = Some(connection);
    }
    Ok(engine.connection.clone().expect("set above"))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
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

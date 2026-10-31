/**
 * Test-only hook: freeze DevDesk at a named moment so a test can SIGKILL it exactly there.
 *
 * <p>Used by: cli; core (process, supervisor); daemon; docker; linux (process).
 */
package io.github.louch.devdesk.core.crashpoint;

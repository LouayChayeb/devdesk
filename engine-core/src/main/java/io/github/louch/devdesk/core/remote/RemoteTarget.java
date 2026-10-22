package io.github.louch.devdesk.core.remote;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Another Linux machine reached with SSH (a server, a stronger box, or a local VM to try it for free): who logs in,
 * where, and the folder the project goes to there.
 *
 * @param identityFile a private key to log in with; "" = the user's usual ones (~/.ssh)
 * @param knownHosts DevDesk's own list of the machines it has seen ("" = ssh's default). New machines are noted
 *     there, so DevDesk never writes into the user's SSH files; the user's own list is still read.
 */
public record RemoteTarget(String user, String host, int port, String folder, String identityFile, String knownHosts) {

    /** With the user's usual keys and known hosts. */
    public RemoteTarget(String user, String host, int port, String folder) {
        this(user, host, port, folder, "", "");
    }

    /** The same machine, with DevDesk's own list of known hosts. */
    public RemoteTarget withKnownHosts(String file) {
        return new RemoteTarget(user, host, port, folder, identityFile, file);
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]_-]+");
    private static final Pattern FOLDER = Pattern.compile("(~/|/)[A-Za-z0-9._/~-]*");
    private static final Pattern FILE = Pattern.compile("(~/|/)[A-Za-z0-9._/-]+");

    /** Why it can't be used, if it can't. The parts end up in commands, so they're checked strictly here. */
    public Optional<String> problem() {
        if (!NAME.matcher(user).matches()) {
            return Optional.of("the user name may only have letters, digits, . _ and -");
        }
        if (!HOST.matcher(host).matches()) {
            return Optional.of("that isn't a host name or address: " + host);
        }
        if (port < 1 || port > 65_535) {
            return Optional.of("the SSH port is 1 to 65535 (usually 22)");
        }
        if (!identityFile.isEmpty() && !FILE.matcher(identityFile).matches()) {
            return Optional.of("the key file must be a full path with plain names, e.g. ~/.ssh/id_ed25519");
        }
        if (!FOLDER.matcher(folder).matches() || folder.contains("..")) {
            return Optional.of("the folder must start with / or ~/ and use plain names, e.g. ~/apps/shop");
        }
        return Optional.empty();
    }

    /** "louay@box.local". */
    public String login() {
        return user + "@" + host;
    }
}

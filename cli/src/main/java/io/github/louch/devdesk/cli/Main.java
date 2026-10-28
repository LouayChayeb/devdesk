package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.changes.KnownGoodFiles;
import io.github.louch.devdesk.linux.diagnosis.LinuxIntrospection;
import io.github.louch.devdesk.linux.doctor.ToolVersions;
import io.github.louch.devdesk.linux.health.LocalHealthProbe;
import io.github.louch.devdesk.linux.network.ProcNetListeners;
import io.github.louch.devdesk.linux.process.GroupLeftovers;
import io.github.louch.devdesk.linux.process.LinuxProcessRuntime;
import io.github.louch.devdesk.linux.process.ProcessLeftovers;
import io.github.louch.devdesk.linux.process.ScopeLeftovers;
import io.github.louch.devdesk.linux.resources.ProcResources;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code devdesk} command-line tool. Wires every dependency by hand. */
@Command(name = "devdesk", mixinStandardHelpOptions = true, description = "Run local multi-service environments.")
public final class Main {

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        // In M1 the engine runs inside the CLI. From M3 the CLI will talk to the daemon instead.
        var clock = Clock.systemUTC();
        var random = new SecureRandom();
        var env = System.getenv();
        var runtime = LinuxProcessRuntime.detect(clock, random, new GroupLeftovers(GroupLeftovers.defaultFolder(env)));
        // Health-check commands are short-lived: a plain process group is enough, and avoids a systemd unit per probe.
        var probe = new LocalHealthProbe(new LinuxProcessRuntime(false, clock, random));
        var trust = new TrustStore(TrustStore.defaultFile(env));
        var system = new LinuxIntrospection(new ProcNetListeners());
        var diagnosis = DiagnosisEngine.standard();
        var knownGood = new KnownGoodFiles(KnownGoodFiles.defaultFolder(env), new ToolVersions(env), clock);
        var engine = new UpCommand.Engine(
                runtime,
                () -> DockerConnection.connect(env),
                probe,
                system,
                diagnosis,
                new ProcessLeftovers(new ScopeLeftovers(), new GroupLeftovers(GroupLeftovers.defaultFolder(env))),
                new ProcResources(),
                trust,
                knownGood,
                clock,
                random);
        var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        return new CommandLine(new Main())
                .addSubcommand(
                        "run", new RunCommand(runtime, Path.of("").toAbsolutePath(), env, System.out, System.err))
                .addSubcommand("up", new UpCommand(engine, env, System.out, System.err))
                .addSubcommand("trust", new TrustCommand(env, trust, stdin, System.out, System.err))
                .addSubcommand(
                        "get",
                        new GetCommand(
                                env,
                                trust,
                                stdin,
                                System.out,
                                System.err,
                                new UpCommand(engine, env, System.out, System.err)))
                .addSubcommand(
                        "once",
                        new OnceCommand(
                                trust,
                                stdin,
                                System.out,
                                System.err,
                                new UpCommand(engine, env, System.out, System.err)))
                .addSubcommand("remove", new RemoveCommand(env, trust, stdin, System.out, System.err))
                .addSubcommand("history", new HistoryCommand(env, clock, System.out, System.err))
                .addSubcommand(
                        "ps",
                        new PsCommand(
                                env,
                                system,
                                new ProcessLeftovers(
                                        new ScopeLeftovers(), new GroupLeftovers(GroupLeftovers.defaultFolder(env))),
                                () -> DockerConnection.connect(env),
                                clock,
                                System.out,
                                System.err))
                .addSubcommand(
                        "doctor",
                        new DoctorCommand(
                                env, system, diagnosis, () -> DockerConnection.connect(env), System.out, System.err))
                .addSubcommand(
                        "why",
                        new WhyCommand(
                                env,
                                system,
                                diagnosis,
                                () -> DockerConnection.connect(env),
                                knownGood,
                                probe,
                                random,
                                clock,
                                System.out,
                                System.err));
    }
}

package com.sakiv.cpi.snapshot;

import com.sakiv.cpi.snapshot.cli.ListCommand;
import com.sakiv.cpi.snapshot.cli.SnapshotCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "cpi-snapshot",
        mixinStandardHelpOptions = true,
        version = "sap-cpi-snapshot 1.0.0",
        description = "Back up SAP Cloud Integration design-time content to a git repository.",
        subcommands = {ListCommand.class, SnapshotCommand.class, CommandLine.HelpCommand.class})
public class App implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String... args) {
        CommandLine cmd = new CommandLine(new App());
        cmd.setExecutionExceptionHandler((ex, line, parsed) -> {
            line.getErr().println("Error: " + ex.getMessage());
            return 2;
        });
        return cmd.execute(args);
    }
}

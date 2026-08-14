import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tlc2.TLC;
import tlc2.output.EC.ExitStatus;

/**
 * Runs one TLC model family in one fresh JVM.  TLC process state is never
 * reused across families; only a model and its focused mutants share startup.
 * Exploration tooling only.
 */
public final class TLCFamilyRunner {
  private TLCFamilyRunner() {}

  private record Check(String expectation, String config, String module) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 5 || (args.length - 2) % 3 != 0) {
      System.err.println(
          "usage: TLCFamilyRunner <model-root> <run-root> "
              + "<valid|mutant> <config> <module> [...]");
      System.exit(2);
    }

    final Path modelRoot = Path.of(args[0]).toAbsolutePath();
    final Path runRoot = Path.of(args[1]).toAbsolutePath();
    Files.createDirectories(runRoot);

    final List<Check> checks = new ArrayList<>();
    for (int index = 2; index < args.length; index += 3) {
      checks.add(new Check(args[index], args[index + 1], args[index + 2]));
    }

    for (int index = 0; index < checks.size(); index++) {
      final Check check = checks.get(index);
      final Path config = modelRoot.resolve(check.config());
      final Path module = modelRoot.resolve(check.module() + ".tla");
      final Path state = runRoot.resolve("state-" + index);
      Files.createDirectories(state);

      final String[] parameters = {
        "-cleanup",
        "-deadlock",
        "-metadir",
        state.toString(),
        "-workers",
        "1",
        "-config",
        config.toString(),
        module.toString()
      };

      final TLC tlc = new TLC();
      if (!tlc.handleParameters(parameters)) {
        System.err.println("TLC rejected parameters for " + check.config());
        System.exit(1);
      }

      final int errorConstant = tlc.process();
      final int exitStatus = ExitStatus.errorConstantToExitStatus(errorConstant);
      final int expectedStatus =
          switch (check.expectation()) {
            case "valid" -> ExitStatus.SUCCESS;
            case "mutant" -> ExitStatus.VIOLATION_SAFETY;
            default -> {
              System.err.println("Unknown expectation: " + check.expectation());
              yield Integer.MIN_VALUE;
            }
          };

      if (exitStatus != expectedStatus) {
        System.err.printf(
            "unexpected TLC classification for %s: error=%d exit=%d expected=%d%n",
            check.config(), errorConstant, exitStatus, expectedStatus);
        System.exit(1);
      }

      if (expectedStatus == ExitStatus.SUCCESS) {
        System.out.println("checked model: " + check.config());
      } else {
        System.out.println("killed mutation: " + check.config());
      }
    }

    // TLC starts helper infrastructure with process-wide static state.  A
    // family runner is deliberately one-shot, even after every check passes.
    System.exit(0);
  }
}

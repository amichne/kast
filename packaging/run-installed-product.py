"""Enter the shared private environment before the passive shell acceptance."""
import os
from pathlib import Path
import subprocess

from acceptance_environment import AcceptanceEnvironment, EnvironmentFailure, EnvironmentRejected, admitted_tools


def main():
    # These four paths are explicit build inputs/outputs, not inherited settings.
    paths = {}
    for key in ("KAST_INSTALLED_PRODUCT", "KAST_CONTROL_ARCHIVE",
                "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_INSTALLED_REPORT_DIRECTORY"):
        value = os.environ.get(key)
        if value is None or not Path(value).is_absolute():
            raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
        paths[key] = str(Path(value).resolve())
    if (not Path(paths["KAST_INSTALLED_PRODUCT"]).is_dir()
            or not Path(paths["KAST_CONTROL_ARCHIVE"]).is_file()
            or not Path(paths["KAST_HOSTED_PLUGIN_ARCHIVE"]).is_file()):
        raise EnvironmentRejected(EnvironmentFailure.INVALID_INPUT)
    with AcceptanceEnvironment(admitted_tools()) as fixture:
        paths["KAST_INSTALLED_PRODUCT"] = str(fixture.stage_product(Path(paths["KAST_INSTALLED_PRODUCT"])))
        environment = dict(fixture.environment)
        subprocess.run([
            str(fixture.tools["bash"]), str(Path(__file__).with_name("test-installed-product.sh")),
            "--isolated-fixture", str(fixture.root),
            "--product", paths["KAST_INSTALLED_PRODUCT"],
            "--control-archive", paths["KAST_CONTROL_ARCHIVE"],
            "--plugin-archive", paths["KAST_HOSTED_PLUGIN_ARCHIVE"],
            "--report-directory", paths["KAST_INSTALLED_REPORT_DIRECTORY"],
        ], env=environment, cwd=fixture.root / "workspace", check=True, timeout=120)
        fixture.mark_passed()


if __name__ == "__main__":
    main()

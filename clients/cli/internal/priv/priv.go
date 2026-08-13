package priv

import (
	"fmt"
	"os"
	"os/exec"
)

var elevatedMarker = "VERITAS_ELEVATED"

func EnsureElevated() error {
	if os.Getenv(elevatedMarker) == "1" {
		return nil
	}

	if os.Getuid() == 0 {
		return nil
	}

	if _, err := exec.LookPath("pkexec"); err != nil {
		return fmt.Errorf("pkexec not found — run with sudo or install polkit")
	}

	exe, err := os.Executable()
	if err != nil {
		exe, err = exec.LookPath("veritas")
		if err != nil {
			return fmt.Errorf("cannot find veritas binary")
		}
	}

	env := os.Environ()
	env = append(env, elevatedMarker+"=1")

	cmd := exec.Command("pkexec", append([]string{exe}, os.Args[1:]...)...)
	cmd.Env = env
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			os.Exit(exitErr.ExitCode())
		}
		return err
	}

	os.Exit(0)
	return nil
}

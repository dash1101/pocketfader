"""PyInstaller entry point (the packaged binaries run this)."""
import sys

from starcast.cli import main
from starcast.config import take_over_beacon_main

if __name__ == "__main__":
    if sys.argv[1:] == ["--take-over-ytm-beacon"]:
        # install-exe.ps1, just before it removes the YTM Beacon app: copy its settings to YTM Starcast
        sys.exit(take_over_beacon_main())
    main()

VERSION=1.0.0-dev.0

all:
	echo "No default" $(VERSION)

flathubLint:
	flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest packaging/flathub/net.vivenotes.ViveNotes.yml


buildFlatpak:
	python3 -B packaging/flatpak/build.py

flatpakInstall:
	buildFlatpak
	flatpak install --user desktopApp/build/flatpak/ViveNotes-$(VERSION)-x86_64.flatpak
	flatpak run net.vivenotes.ViveNotes

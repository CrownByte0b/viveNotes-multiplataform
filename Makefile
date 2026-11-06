VERSION=1.0.2-dev.0

all:
	echo "No default" $(VERSION)

flathubLint:
	flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest packaging/flathub/net.vivenotes.ViveNotes.yml

clean:
	rm -r desktopApp/build/flatpak

buildFlatpak:
	python3 -B packaging/flatpak/build.py

flatpakInstall:
	flatpak install --user desktopApp/build/flatpak/ViveNotes-$(VERSION)-x86_64.flatpak
	flatpak run net.vivenotes.ViveNotes

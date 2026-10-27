all:
	echo "?"

flathubLint:
	flatpak run --filesystem=/tmp:ro --command=flatpak-builder-lint \
	org.flatpak.Builder appstream /tmp/net.vivenotes.ViveNotes.metainfo.xml


buildFlatpak:
	python3 -B packaging/flatpak/build.py

flatpak: buildFlatpak
	flatpak install --user desktopApp/build/flatpak/ViveNotes-1.0.0-x86_64.flatpak
	flatpak run net.vivenotes.ViveNotes

flathub:
	flatpak run --filesystem=/tmp:ro --command=flatpak-builder-lint \
	org.flatpak.Builder appstream /tmp/net.vivenotes.ViveNotes.metainfo.xml

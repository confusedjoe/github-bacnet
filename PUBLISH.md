# Veröffentlichen auf GitHub + Marketplace — Schritt für Schritt

Das Repo ist hier schon fertig vorbereitet und **lokal committet**. Du musst es
nur noch zu GitHub hochladen. Du brauchst dafür nur ein GitHub-Konto.

## 1. Leeres Repo auf GitHub anlegen
1. Auf https://github.com einloggen → oben rechts **„+" → New repository**.
2. Name z. B. `openhab-bacnet-binding`, Sichtbarkeit **Public**.
3. **Nicht** „Add a README/License/.gitignore" anhaken (haben wir schon).
4. **Create repository**. Notiere die angezeigte URL, z. B.
   `https://github.com/DEIN-BENUTZERNAME/openhab-bacnet-binding.git`

## 2. Git-Repo anlegen und hochladen (Eingabeaufforderung / PowerShell)

**Zuerst** einen Rest aus der Vorbereitung entfernen: In diesem Ordner liegt ein
leerer, unvollständiger versteckter Ordner namens `.git`. Lösche ihn einmal — am
einfachsten so (löscht NUR den .git-Ordner, nicht deine Dateien):

```
cd C:\NO_DRIVE\claude\Openhub\github-bacnet
rmdir /s /q .git
```

Dann das Repo frisch anlegen, committen und hochladen:

```
git init
git branch -M main
git add -A
git commit -m "BACnet/IP binding 0.1.0 (experimental)"
git remote add origin https://github.com/DEIN-BENUTZERNAME/openhab-bacnet-binding.git
git push -u origin main
```

Beim ersten `git push` fragt Windows nach deinem GitHub-Login (Browser-Fenster).
Falls `git` nicht gefunden wird: Git for Windows ist installiert (haben wir beim
Bauen schon genutzt) — ggf. die Eingabeaufforderung neu öffnen.

## 3. Release mit der .jar erstellen
Damit die `.jar` eine feste Download-URL bekommt (für Marketplace und „Releases"-Seite):
1. Im Repo auf GitHub: rechts **„Releases" → „Create a new release"**.
2. **Tag:** `v0.1.0`  •  **Title:** `BACnet/IP Binding 0.1.0 (experimental)`.
3. Im Feld **„Attach binaries"** die Datei
   `dist/org.openhab.binding.bacnet-0.1.0.jar` hochladen.
4. **Publish release.** Die Datei hat dann eine URL wie
   `https://github.com/DEIN-BENUTZERNAME/openhab-bacnet-binding/releases/download/v0.1.0/org.openhab.binding.bacnet-0.1.0.jar`

## 4. Marketplace-Beitrag erstellen
Den fertigen Text findest du in `MARKETPLACE-POST.md`. Dort den DOWNLOAD-Link
durch deine Release-URL aus Schritt 3 ersetzen und auf
https://community.openhab.org unter **Add-ons → Marketplace → Bindings** posten.

Danach kann jeder (auch du) das Binding über *Einstellungen → Add-on Store →
Community-Marktplatz* mit einem Klick installieren — und es taucht dann auch
normal in der UI-Bindingliste auf.

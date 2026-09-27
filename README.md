# magis-launcher

App Android TV que al abrirse levanta una VPN WireGuard (config de ProtonVPN embebida en la APK),
abre MagisTV / Xuper TV y desconecta la VPN apenas MagisTV llega a su pantalla principal (tope: 20
segundos). Por la VPN pasa solo el tráfico de MagisTV. Sin pantalla de configuración.

## Setup

1. Bajar una config WireGuard de https://account.protonvpn.com/downloads y guardarla como
   `app/src/main/assets/wireguard.conf` (git-ignored; formato en `wireguard.conf.example`).
   Sin ese archivo el build falla a propósito.
2. `.env` en la raíz (git-ignored) con `KEYSTORE_*` para firmar y `SURGE_EMAIL`/`SURGE_PASSWORD`.

## Uso

- Primera vez: Android pide aceptar la conexión VPN (una sola vez), y la app ofrece ir a Ajustes a
  darle "acceso a datos de uso", que es lo que le permite ver cuándo MagisTV terminó de abrir. Sin
  ese permiso corta la VPN a los 20 segundos.
- Luego cada apertura: busca actualizaciones → conecta → muestra servidor e IP pública → abre MagisTV.
- Si se reabre con la VPN todavía arriba, abre MagisTV directo y reinicia la cuenta de 20s.

## Build y publicación

```bash
./gradlew assembleDebug                      # app/build/outputs/apk/debug/magis-launcher-debug-<version>.apk
scripts/publish_release.sh "changelog"       # bump + release firmado + dist/ + surge
```

URLs: `https://magis-launcher.surge.sh/version.json` y `https://magis-launcher.surge.sh/magis-launcher-release-<version>.apk`

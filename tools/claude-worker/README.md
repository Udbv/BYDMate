# Claude Code Remote Control на Raspberry Pi

Сервіс systemd тримає на Pi запущеним `claude remote-control` у папці конфігу Home Assistant.
Сесія видна в застосунку Claude і на [claude.ai/code](https://claude.ai/code), а Claude працює
прямо з файлами та мережею Pi. Сервіс сам піднімається:
- після перезавантаження Pi;
- після будь-якого завершення процесу (збій, втрата мережі, оновлення), з паузою 15 с.

Документація Remote Control: <https://code.claude.com/docs/en/remote-control>.

## Встановлення (один раз)

Від користувача `udbv` на Pi:

1. **Claude Code** — офіційний інсталятор, є під arm64. Він ставить `claude` у `~/.local/bin`
   і сам оновлюється:
   ```bash
   curl -fsSL https://claude.ai/install.sh | bash
   ```
2. **Вхід** через claude.ai. Remote Control не працює з API-ключем чи `claude setup-token`:
   ```bash
   claude auth login
   ```
3. **Перший запуск вручну**, щоб відповісти на два одноразові питання: довіра до папки й
   увімкнення Remote Control. Без терміналу сервіс не зможе на них відповісти.
   ```bash
   cd /opt/homeassistant/config      # ваш шлях до конфігу HA
   claude remote-control             # y, y; коли з'явиться URL сесії, Ctrl+C
   ```
4. **Сервіс:**
   ```bash
   sudo cp claude-remote-control.service /etc/systemd/system/
   sudo nano /etc/systemd/system/claude-remote-control.service   # User, WorkingDirectory
   sudo systemctl daemon-reload
   sudo systemctl enable --now claude-remote-control
   ```
5. **Перевірка:** `systemctl status claude-remote-control`, `journalctl -u claude-remote-control -f`.
   Сесія «Pi Home Assistant» має з'явитися в списку сесій застосунку Claude.

## Нотатки

- **Не домашня папка.** `WorkingDirectory` не може бути домашньою папкою користувача
  (`/home/<user>`): для неї Claude Code не зберігає довіру і питає щоразу, а під systemd відповісти
  нікому, тож сервіс зависає на питанні. Беріть папку всередині, наприклад конфіг HA Core:
  `/home/homeassistant/.homeassistant`.
- **Той самий користувач.** Установку, `claude auth login` і перший ручний запуск робіть від
  користувача з `User=` юніта: вхід, довіра й підтвердження Remote Control зберігаються для нього.
  Ручний запуск: `sudo -u <user> -H bash -lc 'cd <папка> && claude remote-control'`.

- **Права.** Користувач із `User=` повинен мати право писати в `WorkingDirectory`. Конфіг HA в
  Docker зазвичай належить root. Тоді або дайте `udbv` доступ (`sudo setfacl -R -m u:udbv:rwX`
  на папку, плюс `-d` для нових файлів), або змініть `User=`. Від root юніт не вмикайте без потреби.
- **Оновлення Claude Code** відбуваються самі. Новий бінарник почне працювати, коли процес
  перезапуститься; щоб примусово, виконайте `sudo systemctl restart claude-remote-control`.
- Якщо Remote Control відмовляє через мережу (HTTP 403) довше ~10 хвилин, процес завершується,
  і systemd піднімає його знову.
- Процес робить лише вихідні HTTPS-запити й не відкриває жодних портів на Pi.

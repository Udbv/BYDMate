# Свій GitHub Actions раннер на Raspberry Pi

Раннер для `Udbv/BYDMate` у Docker (`docker compose`), який запускає systemd під час старту системи.
Образ офіційний: `ghcr.io/actions/actions-runner` з [actions/runner](https://github.com/actions/runner),
є під linux/arm64.

Раннер реєструється один раз вручну, одноразовим токеном з інтерфейсу GitHub. Його власні облікові
дані зберігаються в томі Docker `runner-state` і переживають перезапуски та оновлення образу.
Довгоживучого токена GitHub на Pi немає.

| Файл | Що це |
|---|---|
| `docker-compose.yml` | контейнер раннера, мітки `self-hosted`, `bydmate-pi`, `arm64` |
| `start.sh` | одноразова реєстрація (`register`) і звичайний запуск раннера |
| `bydmate-runner.service` | systemd-юніт: `docker compose pull` + `up`, перезапуск при збої |

## Вимоги

- Raspberry Pi з **64-бітною** ОС: Raspberry Pi OS 64-bit або Ubuntu arm64. Перевірка: `uname -m` → `aarch64`.
- Docker Engine з плагіном Compose v2: `docker compose version`.
  Встановлення: `curl -fsSL https://get.docker.com | sh`.

## Встановлення

1. **Файли на Pi:**
   ```bash
   sudo mkdir -p /opt/bydmate-runner
   sudo cp docker-compose.yml start.sh /opt/bydmate-runner/
   ```
2. **Реєстрація (один раз).** На GitHub: Settings → Actions → Runners → *New self-hosted runner*.
   Скопіюйте токен з команди `./config.sh --url … --token XXXX`. Він діє 1 годину. Далі на Pi:
   ```bash
   cd /opt/bydmate-runner
   sudo docker compose run --rm runner register XXXX
   ```
3. **systemd:**
   ```bash
   sudo cp bydmate-runner.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now bydmate-runner
   ```
4. **Перевірка:**
   - `systemctl status bydmate-runner`, `journalctl -u bydmate-runner -f`;
   - на GitHub: Settings → Actions → Runners — раннер `bydmate-pi` у стані *Idle*.

Якщо файли лежать не в `/opt/bydmate-runner`, виправте `WorkingDirectory` у юніті.

## Використання у workflow

```yaml
runs-on: [self-hosted, bydmate-pi]
```

Зараз жоден workflow репозиторію його не використовує: `ci.yml` і `release-dev.yml` працюють на
`ubuntu-latest`. Що переносити на Pi, вирішуємо окремо.

## Безпека

- **Не використовуйте цей раннер у workflow на `pull_request`.** Репозиторій публічний: PR із
  чужого форка виконав би свій код на вашому Pi. Тримайте раннер для `push` у свої гілки та
  `workflow_dispatch`. Додатково: Settings → Actions → General → *Fork pull request workflows* →
  «Require approval for all outside collaborators».
- Облікові дані в томі `runner-state` дозволяють лише брати й виконувати завдання цього
  репозиторію. Доступу до налаштувань репозиторію вони не дають.
- Docker-сокет хоста в контейнер **не** прокинуто, тож завдання не отримає root на Pi через Docker.
  Через це в завданнях не працюють `container:` і `services:`.

## Обслуговування

- **Оновлення:** раннер запускається з `--disableupdate`, а нову версію приносить свіжий образ.
  Юніт робить `docker compose pull` перед кожним стартом, тож достатньо
  `sudo systemctl restart bydmate-runner`. GitHub перестає приймати застарілі версії раннера,
  тому перезапускайте його хоча б раз на місяць: можна таймером systemd або `cron`.
- **Перереєстрація.** Якщо Pi пробуде офлайн понад 14 днів, GitHub сам видалить раннер, і сервіс
  почне падати з помилкою автентифікації. Те саме станеться, якщо видалити раннер вручну. Тоді
  повторіть крок 2 з новим токеном. `--replace` перезапише запис з тим самим іменем:
  ```bash
  sudo systemctl stop bydmate-runner
  cd /opt/bydmate-runner && sudo docker compose run --rm runner register XXXX
  sudo systemctl start bydmate-runner
  ```
- **Робоча папка** (`_work`) живе всередині контейнера і зникає разом з ним під час кожного
  перезапуску сервісу.
- **Видалити раннер:** `sudo systemctl disable --now bydmate-runner`,
  `cd /opt/bydmate-runner && sudo docker compose down -v`, потім прибрати запис у
  Settings → Actions → Runners.

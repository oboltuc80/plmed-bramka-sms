# Upstream

Ten projekt jest forkiem [capcom6/android-sms-gateway](https://github.com/capcom6/android-sms-gateway)
(SMS Gateway for Android™), rozwijanym jako **PL-Med Bramka SMS**.

| | |
|---|---|
| Repozytorium upstream | https://github.com/capcom6/android-sms-gateway |
| Punkt rozgałęzienia | tag `v1.77.5`, commit `49378934aae99387dd7505a600ff50cab06d5281` (2026-10-21) |
| Data forka | 2026-10-23 |
| Licencja upstream | Apache License 2.0 (plik `LICENSE`) |

## Zasady utrzymania zgodności z upstream

- Zmiany brandingowe i konfiguracyjne są trzymane w osobnych, małych commitach;
  oryginalne wartości zostają w komentarzach (`<!-- upstream: ... -->`).
- Funkcjonalność chmurowa nie jest usuwana z kodu — jest wyłączana flavorem buildu,
  żeby merge z upstream nie wymagał rozplątywania.
- API lokalnego serwera (endpointy, format JSON) nie jest modyfikowane.

## Aktualizacja z upstream

```
git remote add upstream https://github.com/capcom6/android-sms-gateway.git   # jednorazowo
git fetch upstream --tags
git merge v<nowa_wersja>
```

Po każdym merge zaktualizować tabelę powyżej (tag i commit).

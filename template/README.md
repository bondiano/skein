# Skein Mod Template

Repo template для модеров: skeleton Clojure-мода на Fabric через Skein.

**Статус:** заглушка. Наполняется в M5 (см. `docs-ai/ROADMAP.md`):

- `build.gradle.kts` с одной строкой Skein-плагина и version catalog;
- Java-стаб-паттерн для mixins (`DESIGN.md` §9.1);
- REPL-конфиг (CIDER/Calva) и getting-started.

Каталог намеренно не включён в корневой Gradle-билд — это standalone-шаблон,
который модеры будут клонировать/генерировать через GitHub template.

## Структура

```
template/
├── settings.gradle.kts        # standalone-билд мода
├── build.gradle.kts           # применяет Skein gradle-plugin (после публикации)
└── src/main/
    ├── clojure/mymod/core.clj # entrypoint
    ├── java/mymod/mixin/      # Java-стабы mixins (§9.1)
    └── resources/fabric.mod.json
```

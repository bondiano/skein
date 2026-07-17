# Skein Mod Template

Repo template для модеров: skeleton Clojure-мода на Fabric через Skein.

**Статус:** заглушка. Планируемое наполнение:

- `build.gradle.kts` с одной строкой Skein-плагина и version catalog;
- Java-стаб-паттерн для mixins;
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
    ├── java/mymod/mixin/      # Java-стабы mixins
    └── resources/fabric.mod.json
```

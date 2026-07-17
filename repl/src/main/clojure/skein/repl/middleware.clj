(ns skein.repl.middleware
  "Opt-in nREPL middleware: каждый eval исполняется на игровом треде.

  Включается на старте сервера (`skein.repl/start!` с
  :game-thread-eval? true, из адаптера — -Dskein.nrepl.game-thread-eval=true).

  Реализация — обёртка кода сообщения в `skein.repl/on-game`: сам eval
  остаётся в сессионном треде nREPL (interrupt работает), а формы
  исполняются на игровом. Ограничение: код сообщения оборачивается в (do
  ...), поэтому позиции в сообщениях об ошибках сдвинуты на префикс
  обёртки."
  (:require [nrepl.middleware :refer [set-descriptor!]]
            [skein.repl]))

(defn wrap-game-thread [handler]
  (fn [{:keys [op code] :as msg}]
    (if (and (= op "eval") (string? code))
      (handler (assoc msg :code (str "(skein.repl/on-game (do " code "\n))")))
      (handler msg))))

(set-descriptor! #'wrap-game-thread
                 {:requires #{}
                  :expects #{"eval"}
                  :handles {}})

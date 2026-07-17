(ns skein.repl.middleware
  "Opt-in nREPL middleware: every eval runs on the game thread.

  Enabled at server start (`skein.repl/start!` with
  :game-thread-eval? true; from the adapter — -Dskein.nrepl.game-thread-eval=true).

  Implemented by wrapping the message code in `skein.repl/on-game`: the
  eval itself stays on the nREPL session thread (interrupt keeps
  working), while the forms execute on the game thread. Limitation: the
  message code is wrapped in a (do ...), so positions in error messages
  are shifted by the wrapper prefix."
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

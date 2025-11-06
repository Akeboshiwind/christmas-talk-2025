(ns app
  (:require ["https://esm.sh/reagami" :as reagami]))

(def state (atom {:counter 0}))

(defn app []
  (let [{:keys [counter]} @state]
    [:h1 "Counter: " counter]))

(defn render []
  (reagami/render (js/document.getElementById "app") [app]))

(add-watch state ::render (fn [_ _ _ _] (render)))

(render)

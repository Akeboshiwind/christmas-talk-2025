(ns app
  (:require ["https://esm.sh/reagami" :as reagami]))


;; >> App

(def state (atom {:counter 0}))

(defn app []
  (let [{:keys [counter]} @state]
    [:div
     [:h1 "Counter: " counter]
     [:button {:on-click #(js/fetch "/api/counter/inc")}
      "Increment"]
     [:button {:on-click #(js/fetch "/api/counter/dec")}
      "Decrement"]]))



;; >> SSE Client

(defn listen
  ([event-source f]
   (listen event-source "message" f))
  ([event-source event f]
   (.addEventListener event-source event f)))

(let [event-source (js/EventSource. "/api/sse")]
  (listen event-source "message" #(js/console.log "Message event:" %))
  (listen event-source "counter"
    (fn [{:keys [data] :as event}]
      (js/console.log "Counter event:" event)
      (swap! state assoc :counter (js/parseInt data)))))



;; >> Render Loop

(defn render []
  (reagami/render (js/document.getElementById "app") [app]))

(add-watch state ::render (fn [_ _ _ _] (render)))

(render)

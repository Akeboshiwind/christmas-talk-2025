(ns talk.audience
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]))


;; >> State

(def state (atom {:slide-id nil
                  :my-votes {}}))  ;; {slide-id -> vote-value}

(def CHANNEL "audience")

(defn current-question []
  (presenter/question-for-slide (:slide-id @state)))

(defn my-vote []
  (get (:my-votes @state) (:slide-id @state)))



;; >> Voting

(defn submit-vote! [value]
  (when-let [q (current-question)]
    (println "Submitting vote:" value)
    (swap! state assoc-in [:my-votes (:slide-id @state)] value)
    (ably/publish! "votes" "vote" {:client-id ably/client-id
                                    :question-id (:id q)
                                    :value value
                                    :timestamp (js/Date.now)})))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-400"
                         :disabled (not (ably/connected?))}
                        attrs)]
        children))

(defn scale-input [{:keys [min max]}]
  (let [current-vote (my-vote)]
    [:div {:class "flex flex-wrap gap-2 justify-center"}
     (for [n (range min (inc max))]
       ^{:key n}
       [button {:class (if (= n current-vote)
                         "px-4 py-2 bg-green-600 text-white rounded-lg"
                         "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700")
                :on-click #(submit-vote! n)}
        (str n)])]))

(defn choice-input [options]
  (let [current-vote (my-vote)]
    [:div {:class "flex flex-col gap-2"}
     (for [opt options]
       ^{:key opt}
       [button {:class (if (= opt current-vote)
                         "px-4 py-2 bg-green-600 text-white rounded-lg"
                         "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700")
                :on-click #(submit-vote! opt)}
        opt])]))

(defn text-input []
  (let [input-id "text-vote-input"]
    [:div {:class "flex flex-col gap-2"}
     [:input {:id input-id
              :type "text"
              :class "px-4 py-2 border rounded-lg"
              :placeholder "Type your answer..."}]
     [button {:on-click (fn []
                          (let [input (js/document.getElementById input-id)
                                value (.-value input)]
                            (when (seq value)
                              (submit-vote! value)
                              (set! (.-value input) ""))))}
      "Submit"]
     (when-let [v (my-vote)]
       [:div {:class "text-sm text-gray-600"} "Your answer: " v])]))

(defn question-ui [question]
  (let [{:keys [text kind options]} question]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} text]
     (case kind
       :scale [scale-input options]
       :choice [choice-input options]
       :text [text-input]
       [:div "Unknown question type"])]))

(defn waiting-ui []
  [:div {:class "text-center text-gray-600"}
   [:h2 {:class "text-xl"} "Waiting for next question..."]
   [:p "The presenter will activate a question soon."]])

(defn audience-ui []
  [:div {:class "p-4 max-w-md mx-auto"}
   [:h1 {:class "text-2xl font-bold mb-4 text-center"} "Vote"]
   (if-let [q (current-question)]
     [question-ui q]
     [waiting-ui])
   [ui/connection-pill]])



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter audience presence (for counting)
  (ably/enter-presence! CHANNEL)

  ;; Watch presenter for current slide
  (presenter/on-state-change!
    (fn []
      (presenter/get-state
        (fn [presenter-state]
          (when presenter-state
            (swap! state assoc :slide-id (:slide-id presenter-state))))))))

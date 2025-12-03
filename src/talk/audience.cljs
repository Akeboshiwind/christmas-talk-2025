(ns talk.audience
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]))

;; >> State

(def state (atom {:slide-id nil
                  :my-votes {}}))  ;; {question-id -> vote-value}

(def CHANNEL "audience")

(defn my-vote-for [question-id]
  (get (:my-votes @state) question-id))



;; >> Voting

(defn submit-vote! [question-id value]
  (swap! state assoc-in [:my-votes question-id] value)
  (ably/publish! "votes" "vote" {:client-id ably/client-id
                                  :question-id question-id
                                  :value value
                                  :timestamp (js/Date.now)}))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-400"
                         :disabled (not (ably/connected?))}
                        attrs)]
        children))

(defn scale-voter [question-id question]
  (let [current-vote (my-vote-for question-id)
        {:keys [min max]} (:options question)]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     [:div {:class "flex flex-wrap gap-2 justify-center"}
      (for [n (range min (inc max))]
        ^{:key n}
        [button {:class (if (= n current-vote)
                          "px-4 py-2 bg-green-600 text-white rounded-lg"
                          "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700")
                 :on-click #(submit-vote! question-id n)}
         (str n)])]]))

(defn choice-voter [question-id question]
  (let [current-vote (my-vote-for question-id)]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     [:div {:class "flex flex-col gap-2"}
      (for [opt (:options question)]
        ^{:key opt}
        [button {:class (if (= opt current-vote)
                          "px-4 py-2 bg-green-600 text-white rounded-lg"
                          "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700")
                 :on-click #(submit-vote! question-id opt)}
         opt])]]))

(defn text-voter [question-id question]
  (let [input-id (str "text-input-" question-id)
        current-vote (my-vote-for question-id)]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     [:div {:class "flex flex-col gap-2"}
      [:input {:id input-id
               :type "text"
               :class "px-4 py-2 border rounded-lg"
               :placeholder "Type your answer..."}]
      [button {:on-click (fn []
                           (let [input (js/document.getElementById input-id)
                                 value (.-value input)]
                             (when (seq value)
                               (submit-vote! question-id value)
                               (set! (.-value input) ""))))}
       "Submit"]
      (when current-vote
        [:div {:class "text-sm text-gray-600"} "Your answer: " current-vote])]]))

(defn question-voter [question-id]
  (let [question (presenter/get-question question-id)]
    (case (:kind question)
      :scale [scale-voter question-id question]
      :choice [choice-voter question-id question]
      :text [text-voter question-id question]
      [:div "Unknown question type"])))

(defn waiting-ui []
  [:div {:class "text-center text-gray-600"}
   [:h2 {:class "text-xl"} "Waiting for next question..."]
   [:p "The presenter will activate a question soon."]])



;; >> Voter Router

(defn render-voter [slide-id]
  (case slide-id
    "q1" [question-voter "q1"]
    "q2" [question-voter "q2"]
    "q3" [question-voter "q3"]
    [waiting-ui]))

(defn audience-ui []
  (let [slide-id (:slide-id @state)]
    [:div {:class "p-4 max-w-md mx-auto"}
     [:h1 {:class "text-2xl font-bold mb-4 text-center"} "Vote"]
     [render-voter slide-id]
     [ui/connection-pill]]))



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

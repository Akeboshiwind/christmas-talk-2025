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



;; >> Utilities

(defn debounce [f delay-ms]
  (let [timeout (atom nil)]
    (fn [& args]
      (when-let [t @timeout]
        (js/clearTimeout t))
      (reset! timeout
        (js/setTimeout #(apply f args) delay-ms)))))



;; >> Voting

(def publish-vote!
  (debounce
    (fn [question-id value]
      (ably/publish! "votes" "vote" {:client-id ably/client-id
                                      :question-id question-id
                                      :value value
                                      :timestamp (js/Date.now)}))
    100))

(defn submit-vote! [question-id value]
  ;; Update local state immediately for responsiveness
  (swap! state assoc-in [:my-votes question-id] value)
  ;; Debounced publish to Ably
  (publish-vote! question-id value))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-400"
                         :disabled (not (ably/connected?))}
                        attrs)]
        children))

(defn scale-voter [question-id question]
  (let [current-vote (my-vote-for question-id)
        {:keys [min max unit]} (:options question)
        value (or current-vote min)
        on-value-change (fn [v]
                          (let [clamped (-> v (cljs.core/max min) (cljs.core/min max))]
                            (submit-vote! question-id clamped)))]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     ;; Value input with optional unit
     [:div {:class "flex items-center justify-center gap-2"}
      [:input {:type "number"
               :min min
               :max max
               :value value
               :class "w-20 text-4xl font-bold text-center border-b-2 border-gray-300 focus:border-blue-500 outline-none"
               :on-change #(let [v (js/parseInt (.. % -target -value))]
                             (when-not (js/isNaN v)
                               (on-value-change v)))}]
      (when unit
        [:span {:class "text-xl text-gray-600"} unit])]
     ;; Slider
     [:div {:class "space-y-1"}
      [:input {:type "range"
               :min min
               :max max
               :value value
               :class "w-full h-3 bg-gray-200 rounded-lg appearance-none cursor-pointer"
               :on-change #(on-value-change (js/parseInt (.. % -target -value)))}]
      ;; Min/max labels
      [:div {:class "flex justify-between text-sm text-gray-500"}
       [:span min]
       [:span max]]]]))

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

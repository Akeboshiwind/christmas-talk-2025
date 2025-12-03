(ns talk.audience
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]))

;; >> State

(def default-state {:slide-id nil
                    :my-votes {}      ;; {question-id -> vote-value}
                    :selected-speaker nil
                    :my-message ""})

(defn save-state! []
  (js/localStorage.setItem "audience-state"
    (js/JSON.stringify (select-keys @state [:my-votes]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "audience-state")]
    (js/JSON.parse saved)))

(def state (atom (merge default-state (load-state))))

(defn reset-state! []
  (js/localStorage.removeItem "audience-state")
  (reset! state default-state)
  ;; Re-fetch presenter state
  (presenter/get-state
    (fn [presenter-state]
      (when presenter-state
        (swap! state assoc
               :slide-id (:slide-id presenter-state)
               :selected-speaker (:selected-speaker presenter-state))))))

(def CHANNEL "audience")
(def SPEAKER-CHANNEL "speaker")
(def REACTIONS-CHANNEL "reactions")

(defn my-vote-for [question-id]
  (get (:my-votes @state) question-id))

(defn i-am-speaker? []
  (= ably/client-id (:selected-speaker @state)))



;; >> Utilities

(defn debounce [f delay-ms]
  (let [timeout (atom nil)]
    (fn [& args]
      (when-let [t @timeout]
        (js/clearTimeout t))
      (reset! timeout
        (js/setTimeout #(apply f args) delay-ms)))))



;; >> Speaker Messages

(defn send-speaker-message! [message]
  (swap! state assoc :my-message message)
  (ably/publish! SPEAKER-CHANNEL "message" {:client-id ably/client-id
                                             :message message
                                             :timestamp (js/Date.now)}))



;; >> Emoji Reactions

(def send-reaction!
  (debounce
    (fn [emoji]
      (ably/publish! REACTIONS-CHANNEL "reaction" {:emoji emoji
                                                    :id (str (random-uuid))
                                                    :timestamp (js/Date.now)}))
    200))



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
  (save-state!)
  ;; Debounced publish to Ably
  (publish-vote! question-id value))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-600"
                         :disabled (not (ably/connected?))}
                        attrs)]
        children))

(defn scale-voter [question-id question]
  (let [current-vote (my-vote-for question-id)
        {:keys [min max unit min-label max-label]} (:options question)
        value (or current-vote min)
        on-value-change (fn [v]
                          (let [clamped (js/Math.min max (js/Math.max min v))]
                            (submit-vote! question-id clamped)))]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-xl font-semibold text-center"} (:text question)]
     ;; Value input with optional unit
     [:div {:class "flex items-center justify-center gap-2"}
      [:input {:type "number"
               :min min
               :max max
               :value value
               :class "w-full text-4xl font-bold text-center border-b-2 border-gray-600 focus:border-blue-500 outline-none bg-transparent"
               :on-change #(let [v (js/parseInt (.. % -target -value))]
                             (when-not (js/isNaN v)
                               (on-value-change v)))}]
      (when unit
        [:span {:class "text-xl text-gray-400"} unit])]
     ;; Slider
     [:div {:class "space-y-1"}
      [:input {:type "range"
               :min min
               :max max
               :value value
               :class "w-full h-3 bg-gray-700 rounded-lg appearance-none cursor-pointer"
               :on-change #(on-value-change (js/parseInt (.. % -target -value)))}]
      ;; Min/max labels
      [:div {:class "flex justify-between text-sm text-gray-400"}
       [:span (or min-label min)]
       [:span (or max-label max)]]]]))

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
               :class "px-4 py-2 border border-gray-600 rounded-lg bg-transparent"
               :placeholder "Type your answer..."}]
      [button {:on-click (fn []
                           (let [input (js/document.getElementById input-id)
                                 value (.-value input)]
                             (when (seq value)
                               (submit-vote! question-id value)
                               (set! (.-value input) ""))))}
       "Submit"]
      (when current-vote
        [:div {:class "text-sm text-gray-400"} "Your answer: " current-vote])]]))

(defn question-voter [question-id]
  (let [question (presenter/get-question question-id)]
    (case (:kind question)
      :scale [scale-voter question-id question]
      :choice [choice-voter question-id question]
      :text [text-voter question-id question]
      [:div "Unknown question type"])))

(defn speaker-message-input []
  (let [input-id "speaker-message-input"
        current-message (:my-message @state)]
    [:div {:class "p-4 bg-purple-900/50 border border-purple-500 rounded-lg space-y-3"}
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-purple-400 text-lg"} "✨"]
      [:span {:class "text-purple-300 font-semibold"} "You've been selected to share!"]]
     [:textarea {:id input-id
                 :class "w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded-lg text-white resize-none focus:border-purple-500 focus:outline-none"
                 :rows 3
                 :placeholder "Share your thoughts..."
                 :default-value current-message}]
     [button {:class "w-full px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-gray-600"
              :on-click (fn []
                          (let [input (js/document.getElementById input-id)
                                value (.-value input)]
                            (when (seq (.trim value))
                              (send-speaker-message! value)
                              (set! (.-value input) ""))))}
      "Send Message"]
     (when (seq current-message)
       [:div {:class "text-xs text-gray-400"} "Your message is live on screen"])]))

(def reaction-emojis ["❤️" "👍" "😮" "🧠"])

(defn emoji-button [emoji]
  [:button {:class "text-4xl p-3 hover:scale-125 transition-transform active:scale-90"
            :on-click #(send-reaction! emoji)}
   emoji])

(defn waiting-ui []
  [:div {:class "text-center text-gray-400 space-y-8"}
   [:div
    [:h2 {:class "text-xl"} "Waiting for next question..."]
    [:p "The presenter will activate a question soon."]]
   [:div {:class "flex justify-center gap-4"}
    (for [emoji reaction-emojis]
      ^{:key emoji}
      [emoji-button emoji])]])



;; >> Voter Router

(defn render-voter [slide-id]
  (case slide-id
    "q1" [question-voter "q1"]
    "q2" [question-voter "q2"]
    "q3" [:div {:class "space-y-4"}
          [question-voter "q3"]
          (when (i-am-speaker?)
            [speaker-message-input])]
    "q4" [question-voter "q4"]
    "q5" [question-voter "q5"]
    [waiting-ui]))

(defn audience-ui []
  (let [slide-id (:slide-id @state)]
    [:div {:class "min-h-screen bg-gray-900 text-white"}
     [:div {:class "p-4 max-w-md mx-auto"}
      [:h1 {:class "text-2xl font-bold mb-4 text-center"} "Vote"]
      [render-voter slide-id]
      [ui/connection-pill]]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter audience presence (for counting)
  (ably/enter-presence! CHANNEL)

  ;; Watch presenter for current slide and selected speaker
  (presenter/on-state-change!
    (fn []
      (presenter/get-state
        (fn [presenter-state]
          (when presenter-state
            (swap! state assoc
                   :slide-id (:slide-id presenter-state)
                   :selected-speaker (:selected-speaker presenter-state)))))))

  ;; Subscribe to control channel for reset
  (ably/subscribe! "control" (fn [_] (reset-state!))))

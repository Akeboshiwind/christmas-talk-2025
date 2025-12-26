(ns talk.display
  (:require ["./ably.js" :as ably]
            ["./presenter.js" :as presenter]
            ["./ui.js" :as ui]
            ["qrcode" :as QRCode]
            [clojure.string :as str]))

;; Detect base path from URL at runtime (strip trailing slash)
(def base-path
  (let [pathname (.-pathname js/location)]
    (if (str/ends-with? pathname "/")
      (subs pathname 0 (dec (count pathname)))
      pathname)))

(def persimmon-img (str base-path "/persimmon.jpeg"))
(def join-url "192.168.1.184:3000")


;; >> State

(def default-state {:slide-id nil
                    :votes {}  ;; {question-id -> {:latest-vote {client-id -> vote}, :all-votes [vote]}}
                    :players {}  ;; {client-id -> {:name "..." :client-id "..."}}
                    :audience-count 0
                    :reactions []
                    :qr-code-data nil})

(defn save-state! []
  (js/localStorage.setItem "display-state"
    (js/JSON.stringify (select-keys @state [:votes]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "display-state")]
    (js/JSON.parse saved)))

(def state (atom (merge default-state (load-state))))

(defn reset-state! []
  (js/localStorage.removeItem "display-state")
  (reset! state default-state)
  ;; Re-fetch presenter state
  (presenter/get-state
    (fn [presenter-state]
      (when presenter-state
        (swap! state assoc :slide-id (:slide-id presenter-state))))))



;; >> Vote Processing

(defn process-vote [vote]
  (let [client-id (:client-id vote)
        question-id (:question-id vote)]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:votes question-id :latest-vote client-id] vote)
                       (update-in [:votes question-id :all-votes] (fnil conj []) vote))))
    (save-state!)))



;; >> Player Helpers

(defn get-player-name [client-id]
  (or (get-in @state [:players client-id :name])
      (subs client-id 0 8)))

(defn get-players []
  (vals (:players @state)))



;; >> Reaction Processing

(def MAX-REACTIONS 100)
(def REACTION-LIFETIME 4000) ;; ms

(defn process-reaction [reaction]
  (let [x (+ 10 (rand-int 80))  ;; random x position 10-90%
        new-reaction (assoc reaction :x x :created-at (js/Date.now))]
    (swap! state update :reactions
           (fn [reactions]
             (->> (conj reactions new-reaction)
                  (take-last MAX-REACTIONS)
                  vec)))))

(defn cleanup-old-reactions! []
  (let [now (js/Date.now)
        cutoff (- now REACTION-LIFETIME)]
    (swap! state update :reactions
           (fn [reactions]
             (filterv #(> (:created-at %) cutoff) reactions)))))



;; >> Aggregation

(defn get-votes-for [question-id]
  (vals (get-in @state [:votes question-id :latest-vote])))

(defn scale-stats [question-id]
  (let [votes (get-votes-for question-id)
        values (map :value votes)]
    (when (seq values)
      {:count (count values)
       :average (/ (reduce + values) (count values))
       :distribution (frequencies values)})))

(defn choice-stats [question-id]
  (frequencies (map :value (get-votes-for question-id))))

(defn text-responses [question-id]
  (map :value (get-votes-for question-id)))

(defn order-responses [question-id]
  "Returns list of order arrays submitted"
  (map :value (get-votes-for question-id)))

(defn date-responses [question-id]
  "Returns list of date strings submitted"
  (map :value (get-votes-for question-id)))

(defn response-count [question-id]
  (count (get-votes-for question-id)))

;; >> Scoring

(defn check-answer [question-id player-answer]
  "Returns true if answer is correct, false otherwise"
  (let [question (presenter/get-question question-id)
        correct (:answer question)]
    (when correct
      (case (:kind question)
        :choice (= player-answer correct)
        :scale (= player-answer correct)
        false))))

(defn get-player-score [client-id]
  "Calculate total score for a player across all questions"
  (reduce
    (fn [score question-id]
      (let [vote (get-in @state [:votes question-id :latest-vote client-id])
            correct? (when vote (check-answer question-id (:value vote)))]
        (if correct? (inc score) score)))
    0
    presenter/question-ids))

(defn get-all-scores []
  "Returns sorted list of {:client-id :name :score}"
  (let [players (get-players)]
    (->> players
         (map (fn [player]
                {:client-id (:client-id player)
                 :name (or (:name player) "Unknown")
                 :score (get-player-score (:client-id player))}))
         (sort-by :score >))))

(defn running-averages [question-id]
  "Returns a vector of {:timestamp :average :count} for each vote received,
   where average is computed from the latest vote per client at that point in time"
  (let [all-votes (get-in @state [:votes question-id :all-votes] [])
        sorted-votes (sort-by :timestamp all-votes)]
    (loop [votes sorted-votes
           client-votes {}  ;; {client-id -> value}
           results []]
      (if (empty? votes)
        results
        (let [{:keys [client-id value timestamp]} (first votes)
              new-client-votes (assoc client-votes client-id value)
              values (vals new-client-votes)
              new-avg (/ (reduce + values) (count values))]
          (recur (rest votes)
                 new-client-votes
                 (conj results {:timestamp timestamp
                                :average new-avg
                                :count (count new-client-votes)})))))))



;; >> Floating Reactions Layer

(defn floating-emoji [{:keys [emoji x id created-at]}]
  (let [age-ms (- (js/Date.now) created-at)
        ;; Negative delay "fast forwards" the animation to the right point
        delay-s (/ (- age-ms) 1000)]
    [:div {:key id
           :class "absolute text-5xl pointer-events-none animate-float-up"
           :style {:left (str x "%")
                   :bottom "0%"
                   :transform "translateX(-50%)"
                   :animation-delay (str delay-s "s")}}
     emoji]))

(defn reactions-layer []
  (let [reactions (:reactions @state)]
    [:div {:class "fixed inset-0 overflow-hidden pointer-events-none z-50"}
     [:style "
       @keyframes float-up {
         0% { bottom: 0%; opacity: 1; }
         100% { bottom: 100%; opacity: 0; }
       }
       .animate-float-up {
         animation: float-up 4s linear forwards;
       }
     "]
     (for [reaction reactions]
       ^{:key (:id reaction)}
       [floating-emoji reaction])]))



;; >> Slide Wrapper

(defn slide-wrapper [content]
  (let [slide-id (:slide-id @state)
        slide-ids presenter/slide-ids
        idx (.indexOf slide-ids slide-id)
        total (count slide-ids)]
    [:div {:class "w-screen h-screen bg-gray-900 text-white relative"}
     ;; Main content
     [:div {:class "w-full h-full flex items-center justify-center p-8"}
      content]
     ;; Footer
     [:div {:class "absolute bottom-4 left-4 text-gray-400 text-sm"}
      (:audience-count @state) " 👥"]
     [:div {:class "absolute bottom-4 right-4 text-gray-400 text-sm"}
      (inc idx) " of " total]
     [ui/connection-pill]]))



;; >> Content Slides

(defn title-slide []
  (let [players (get-players)]
    [slide-wrapper
     [:div {:class "text-center"}
      [:h1 {:class "text-7xl font-bold mb-8"} "Quiz"]
      [:div {:class "flex justify-center gap-16 items-start"}
       ;; QR Code
       [:div
        (when (:qr-code-data @state)
          [:img {:src (:qr-code-data @state) :class "mx-auto mb-4 rounded-lg"}])
        [:p {:class "text-2xl text-gray-400"} "Join at " join-url]]
       ;; Players list
       [:div {:class "text-left"}
        [:h2 {:class "text-2xl font-semibold mb-4 text-gray-300"} "Players"]
        (if (seq players)
          [:div {:class "space-y-2"}
           (for [player players]
             ^{:key (:client-id player)}
             [:div {:class "flex items-center gap-3"}
              [:span {:class "text-green-400"} "●"]
              [:span {:class "text-xl"}
               (or (:name player) "Joining...")]])]
          [:p {:class "text-gray-500"} "Waiting for players..."])]]]]))

(defn about-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "What is this talk about?"]]])

(defn wotc-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "What is Wisdom of the Crowd?"]]])

(defn wotc-answer-slide []
  [slide-wrapper
   [:div {:class "text-center space-y-8"}
    [:p {:class "text-4xl"} "Ask a crowd a question"]
    [:p {:class "text-4xl"} "get a surprisingly accurate answer back"]]])

(defn rules-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "First some ground rules"]]])

(defn independence-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "Independence"]]])

(defn diversity-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "Diversity"]]])

(defn ground-truth-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold"} "A ground truth exists"]]])

(defn point-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold mb-8"} "So what's the point?"]
    [:p {:class "text-3xl text-gray-400"} "Does this sound familiar to any widely used technology currently?"]]])

(defn search-engines-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-6xl font-bold"} "Search Engines!"]]])

(defn llms-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-6xl font-bold"} "LLMs"]]])

(defn but-not-really-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold mb-12"} "But not really"]
    [:table {:class "mx-auto text-2xl border-collapse"}
     [:thead
      [:tr {:class "border-b border-gray-600"}
       [:th {:class "px-8 py-4 text-left"}]
       [:th {:class "px-8 py-4"} "Wisdom of the Crowd"]
       [:th {:class "px-8 py-4"} "LLMs"]]]
     [:tbody
      [:tr {:class "border-b border-gray-700"}
       [:td {:class "px-8 py-4 text-left text-gray-400"} "Independence"]
       [:td {:class "px-8 py-4 text-green-400"} "✅"]
       [:td {:class "px-8 py-4 text-red-400"} "❌"]]
      [:tr {:class "border-b border-gray-700"}
       [:td {:class "px-8 py-4 text-left text-gray-400"} "Diversity"]
       [:td {:class "px-8 py-4 text-green-400"} "✅"]
       [:td {:class "px-8 py-4 text-green-400"} "✅"]]
      [:tr
       [:td {:class "px-8 py-4 text-left text-gray-400"} "Requires Ground Truth"]
       [:td {:class "px-8 py-4 text-green-400"} "✅"]
       [:td {:class "px-8 py-4 text-red-400"} "❌"]]]]]])

(defn the-end-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-7xl font-bold"} "The end"]]])

(def github-repo-url "https://github.com/Akeboshiwind/christmas-talk-2025")

(defn tech-stack-slide []
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-5xl font-bold mb-8"} "Tech Stack"]
    [:div {:class "flex justify-center gap-16"}
     ;; Left column - tech stack
     [:div {:class "space-y-4 text-left"}
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/squint.png")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Squint"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/babashka.png")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Babashka"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/babashka.png")
              :class "w-12 h-12 opacity-50"}]
       [:span {:class "text-2xl"} "Reagami"]
       [:span {:class "text-sm text-gray-400"} "(yet another borkdude project)"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/ably.png")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Ably"]
       [:span {:class "text-sm text-gray-400"} "(for the realtime stuff)"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/bun.svg")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Bun"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/tailwind.svg")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Tailwind"]]
      [:div {:class "flex items-center gap-4"}
       [:img {:src (str base-path "/logos/claude.png")
              :class "w-12 h-12"}]
       [:span {:class "text-2xl"} "Claude Code"]
       [:span {:class "text-sm text-gray-400"} "(a whole lot of it)"]]]
     ;; Right column - QR code
     [:div {:class "flex flex-col items-center justify-center"}
      (when (:github-qr-code-data @state)
        [:img {:src (:github-qr-code-data @state) :class "rounded-lg mb-2"}])
      [:p {:class "text-sm text-gray-400"} "View source"]]]]])


;; >> Speaker Message Display (tweet-style)

(defn speaker-message-display []
  (let [messages (:speaker-messages @state)
        valid-messages (filter #(seq (:message %)) messages)
        selected-speaker (:selected-speaker @state)
        speaker-client-id (or (:client-id (first messages)) selected-speaker)
        speaker-vote (when speaker-client-id
                       (get-in @state [:votes "q3" :latest-vote speaker-client-id :value]))
        should-show? (or (seq valid-messages) speaker-vote)]
    (when should-show?
      [:div {:class "absolute top-8 right-8 max-w-md space-y-3"}
       ;; Header card
       [:div {:class "bg-gray-800 rounded-2xl p-3 shadow-xl border border-gray-700"}
        [:div {:class "flex items-center gap-3"}
         [:div {:class "w-10 h-10 rounded-full bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center text-lg"}
          "🌟"]
         [:div {:class "flex-1"}
          [:div {:class "font-bold text-white"} "Cool influencer"]
          [:div {:class "text-gray-400 text-sm"} "Live from the audience"]]
         (when speaker-vote
           [:div {:class "text-2xl font-bold text-blue-400"}
            speaker-vote])]]
       ;; Messages (progressively fade: 100%, 80%, 60%, 40%, 20%)
       (for [[idx msg] (map-indexed vector valid-messages)]
         (let [opacity (- 100 (* idx 20))]
           ^{:key (:timestamp msg)}
           [:div {:class "bg-gray-800 rounded-xl p-3 shadow-lg border border-gray-700"
                  :style {:opacity (/ opacity 100)}}
            [:div {:class "text-white text-lg leading-relaxed"}
             (:message msg)]]))])))



;; >> Q3 Slide with Speaker Message

(defn q3-slide []
  (let [question (presenter/get-question "q3")
        responses (response-count "q3")
        audience (:audience-count @state)]
    [slide-wrapper
     [:div {:class "text-center"}
      [:h1 {:class "text-5xl font-bold mb-8"} (:text question)]
      [:p {:class "text-2xl text-gray-400"} responses " / " audience " Responses Received"]
      [speaker-message-display]]]))



;; >> Question Slides

(defn question-slide [question-id]
  (let [question (presenter/get-question question-id)
        responses (response-count question-id)
        audience (:audience-count @state)
        kind (:kind question)
        ;; Get question number from ID (e.g., "q3" -> 3)
        q-num (js/parseInt (subs question-id 1))
        total-qs (count presenter/question-ids)]
    [slide-wrapper
     [:div {:class "text-center"}
      [:p {:class "text-2xl text-gray-500 mb-4"} "Question " q-num " / " total-qs]
      [:h1 {:class "text-5xl font-bold mb-8"} (:text question)]
      ;; Show type-specific subtitle/content
      (case kind
        :choice
        [:ul {:class "text-3xl text-left max-w-lg mx-auto space-y-4 mb-8"}
         (for [opt (:options question)]
           ^{:key opt}
           [:li {:class "flex items-center gap-4"}
            [:span {:class "text-blue-400"} "•"]
            [:span opt]])]

        :scale
        (let [{:keys [min max]} (:options question)]
          [:p {:class "text-3xl text-gray-400 mb-8"}
           "Select a number from " min " to " max])

        nil)
      [:p {:class "text-2xl text-gray-400"} responses " / " audience " Responses Received"]]]))



;; >> Analysis Slide

(defn scale-chart
  ([question-id question] (scale-chart question-id question nil))
  ([question-id question answer]
   (let [stats (scale-stats question-id)
         min-val (get-in question [:options :min])
         max-val (get-in question [:options :max])
         unit (get-in question [:options :unit])
         min-label (get-in question [:options :min-label])
         max-label (get-in question [:options :max-label])
         range-size (- max-val min-val)
         bin-size (or (get-in question [:options :bin-size])
                      (js/Math.ceil (/ (inc range-size) (js/Math.min 10 (inc range-size)))))
         num-bins (js/Math.ceil (/ (inc range-size) bin-size))
         bins (vec (for [i (range num-bins)]
                    (let [bin-start (+ min-val (* i bin-size))
                          bin-end (js/Math.min max-val (+ bin-start (dec bin-size)))]
                      {:start bin-start
                       :end bin-end
                       :count (if stats
                                (reduce + (for [v (range bin-start (inc bin-end))]
                                            (get (:distribution stats) v 0)))
                                0)})))
         max-count (apply max 1 (map :count bins))]
     (if stats
       [:div {:class "text-center space-y-4"}
        [:div {:class "flex items-baseline justify-center gap-6"}
         [:div {:class "text-8xl font-bold text-blue-400"}
          (.toFixed (:average stats) 1)
          (when unit [:span {:class "text-4xl ml-2"} unit])]
         (when answer
           [:div {:class "text-4xl text-green-400"}
            "(" answer (when unit [:span {:class "text-2xl ml-1"} unit]) ")"])]
        [:div {:class "text-xl text-gray-400"}
         "Average from " (:count stats) " responses"]
        [:div {:class "flex justify-center items-end gap-1 mt-8 px-4"}
         (for [{:keys [start end count]} bins]
           (let [height (if (pos? max-count) (* 150 (/ count max-count)) 0)]
             ^{:key start}
             [:div {:class "flex-1 text-center min-w-0"}
              [:div {:class "bg-blue-500 rounded-t mx-auto"
                     :style {:height (str height "px")}}]
              [:div {:class "text-xs text-gray-400 mt-1 truncate"}
               (if (= start end) start (str start "-" end))]]))]
        (when (or min-label max-label)
          [:div {:class "flex justify-between text-sm text-gray-400 mt-2 px-4"}
           [:span (or min-label "")]
           [:span (or max-label "")]])]
       [:div {:class "text-2xl text-gray-500"} "No responses yet"]))))

(defn choice-chart [question-id question]
  (let [stats (choice-stats question-id)
        total (reduce + (vals stats))]
    (if (pos? total)
      [:div {:class "w-full max-w-2xl space-y-4"}
       (for [opt (:options question)]
         (let [cnt (get stats opt 0)
               pct (if (pos? total) (* 100 (/ cnt total)) 0)]
           ^{:key opt}
           [:div {:class "flex items-center gap-4"}
            [:div {:class "w-40 text-right text-lg"} opt]
            [:div {:class "flex-1 bg-gray-700 rounded h-10"}
             [:div {:class "bg-blue-500 h-10 rounded flex items-center justify-end pr-2"
                    :style {:width (str pct "%")}}
              (when (pos? cnt)
                [:span {:class "text-sm font-bold"} cnt])]]
            [:div {:class "w-16 text-gray-400"} (.toFixed pct 0) "%"]]))]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn text-list [question-id]
  (let [responses (text-responses question-id)]
    (if (seq responses)
      [:div {:class "w-full max-w-2xl max-h-96 overflow-y-auto space-y-2"}
       (for [[idx resp] (map-indexed vector responses)]
         ^{:key idx}
         [:div {:class "p-3 bg-gray-700 rounded text-lg"} resp])]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn order-list [question-id question]
  (let [responses (order-responses question-id)
        {:keys [top-label bottom-label]} (:options question)]
    (if (seq responses)
      [:div {:class "w-full max-w-2xl space-y-4"}
       [:div {:class "text-sm text-gray-400 text-center mb-2"}
        (str (count responses) " responses")]
       [:div {:class "space-y-2"}
        (for [[idx resp] (map-indexed vector responses)]
          ^{:key idx}
          [:div {:class "p-3 bg-gray-700 rounded flex items-center gap-4"}
           [:span {:class "text-gray-500 text-sm"} (inc idx) "."]
           [:div {:class "flex-1 flex gap-2"}
            (for [[i item] (map-indexed vector resp)]
              ^{:key i}
              [:span {:class (str "px-2 py-1 rounded text-sm "
                                   (if (zero? i) "bg-blue-600" "bg-gray-600"))}
               item])]])]]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn date-list [question-id]
  (let [responses (date-responses question-id)
        freq (frequencies responses)
        sorted-dates (sort-by first (seq freq))]
    (if (seq responses)
      [:div {:class "w-full max-w-2xl space-y-4"}
       [:div {:class "text-sm text-gray-400 text-center mb-2"}
        (str (count responses) " responses")]
       [:div {:class "space-y-2"}
        (for [[date cnt] sorted-dates]
          ^{:key date}
          [:div {:class "p-3 bg-gray-700 rounded flex justify-between"}
           [:span {:class "text-lg"} date]
           [:span {:class "text-blue-400 font-bold"} cnt]])]]
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn format-answer [question value]
  "Format an answer for display based on question type"
  (str value))

(defn player-answers-display [question-id]
  "Shows each player's answer with correct/incorrect indication"
  (let [question (presenter/get-question question-id)
        correct-answer (:answer question)
        players (get-players)
        votes (get-votes-for question-id)]
    [:div {:class "w-full max-w-3xl space-y-4"}
     ;; Show correct answer if exists
     (when correct-answer
       [:div {:class "mb-6 p-4 bg-green-900/30 border border-green-700 rounded-lg"}
        [:div {:class "text-sm text-green-400 mb-1"} "Correct Answer"]
        [:div {:class "text-2xl font-bold text-green-300"}
         (format-answer question correct-answer)]])
     ;; Show each player's answer
     [:div {:class "space-y-3"}
      (for [player players]
        (let [client-id (:client-id player)
              vote (first (filter #(= (:client-id %) client-id) votes))
              player-answer (:value vote)
              correct? (when (and correct-answer player-answer)
                         (check-answer question-id player-answer))]
          ^{:key client-id}
          [:div {:class (str "p-4 rounded-lg flex items-center justify-between "
                             (cond
                               (nil? player-answer) "bg-gray-800 border border-gray-700"
                               correct? "bg-green-900/30 border border-green-700"
                               (and correct-answer (not correct?)) "bg-red-900/30 border border-red-700"
                               :else "bg-gray-700"))}
           [:div {:class "flex items-center gap-3"}
            [:span {:class "text-xl font-semibold"} (or (:name player) "Unknown")]
            (when correct-answer
              (cond
                (nil? player-answer) [:span {:class "text-gray-500"} "—"]
                correct? [:span {:class "text-green-400 text-xl"} "✓"]
                :else [:span {:class "text-red-400 text-xl"} "✗"]))]
           [:div {:class "text-right text-lg"}
            (if player-answer
              (format-answer question player-answer)
              [:span {:class "text-gray-500 italic"} "No answer"])]]))]]))

(defn analysis-slide [question-id]
  (let [question (presenter/get-question question-id)]
    [slide-wrapper
     [:div {:class "text-center w-full flex flex-col items-center"}
      [:h1 {:class "text-4xl font-bold mb-8"} (:text question)]
      [player-answers-display question-id]]]))



;; >> Trend Graph (Average over time)

(defn trend-graph [question-id answer]
  (let [data (running-averages question-id)
        question (presenter/get-question question-id)
        min-val (get-in question [:options :min])
        max-val (get-in question [:options :max])
        unit (get-in question [:options :unit])
        ;; SVG dimensions
        width 800
        height 400
        padding 60
        graph-width (- width (* 2 padding))
        graph-height (- height (* 2 padding))]
    (if (seq data)
      (let [min-time (:timestamp (first data))
            max-time (:timestamp (last data))
            time-range (max 1 (- max-time min-time))
            value-range (- max-val min-val)
            ;; Scale functions
            x-scale (fn [t] (+ padding (* graph-width (/ (- t min-time) time-range))))
            y-scale (fn [v] (- (+ padding graph-height) (* graph-height (/ (- v min-val) value-range))))
            ;; Build path
            path-points (map-indexed
                         (fn [i {:keys [timestamp average]}]
                           (let [x (x-scale timestamp)
                                 y (y-scale average)]
                             (if (zero? i)
                               (str "M " x " " y)
                               (str "L " x " " y))))
                         data)
            path-d (str/join " " path-points)
            final-avg (:average (last data))]
        [:div {:class "w-full flex flex-col items-center"}
         [:svg {:width width :height height :class "bg-gray-800 rounded-lg"}
          ;; Y-axis
          [:line {:x1 padding :y1 padding :x2 padding :y2 (+ padding graph-height)
                  :stroke "#4B5563" :stroke-width 2}]
          ;; X-axis
          [:line {:x1 padding :y1 (+ padding graph-height) :x2 (+ padding graph-width) :y2 (+ padding graph-height)
                  :stroke "#4B5563" :stroke-width 2}]
          ;; Y-axis labels
          (for [v [min-val (/ (+ min-val max-val) 2) max-val]]
            ^{:key v}
            [:text {:x (- padding 10) :y (y-scale v) :fill "#9CA3AF" :text-anchor "end" :dominant-baseline "middle" :font-size 14}
             v])
          ;; Answer line (horizontal)
          (when answer
            [:g
             [:line {:x1 padding :y1 (y-scale answer) :x2 (+ padding graph-width) :y2 (y-scale answer)
                     :stroke "#34D399" :stroke-width 2 :stroke-dasharray "8,4"}]
             [:text {:x (+ padding graph-width 10) :y (y-scale answer) :fill "#34D399" :dominant-baseline "middle" :font-size 14}
              (str answer (when unit unit))]])
          ;; Data line
          [:path {:d path-d :fill "none" :stroke "#60A5FA" :stroke-width 3}]
          ;; Data points
          (for [{:keys [timestamp average]} data]
            ^{:key timestamp}
            [:circle {:cx (x-scale timestamp) :cy (y-scale average) :r 4 :fill "#60A5FA"}])
          ;; Final average label
          [:text {:x (+ (x-scale (:timestamp (last data))) 10)
                  :y (y-scale final-avg)
                  :fill "#60A5FA" :dominant-baseline "middle" :font-size 16 :font-weight "bold"}
           (.toFixed final-avg 1)]]
         [:div {:class "mt-4 text-xl text-gray-400"}
          "Average over " (count data) " responses"]])
      [:div {:class "text-2xl text-gray-500"} "No responses yet"])))

(defn trend-slide [question-id answer]
  (let [question (presenter/get-question question-id)]
    [slide-wrapper
     [:div {:class "text-center w-full"}
      [:h1 {:class "text-4xl font-bold mb-8"} "Convergence Over Time"]
      [trend-graph question-id answer]]]))



;; >> Scoreboard Slides

(defn scores-display [title]
  (let [scores (get-all-scores)
        max-score (count (filter #(:answer (presenter/get-question %)) presenter/question-ids))]
    [slide-wrapper
     [:div {:class "text-center w-full"}
      [:h1 {:class "text-6xl font-bold mb-12"} title]
      [:div {:class "max-w-2xl mx-auto space-y-4"}
       (for [[idx {:keys [client-id name score]}] (map-indexed vector scores)]
         (let [rank (inc idx)
               medal (case rank 1 "🥇" 2 "🥈" 3 "🥉" nil)]
           ^{:key client-id}
           [:div {:class (str "p-6 rounded-xl flex items-center justify-between "
                              (case rank
                                1 "bg-yellow-900/40 border-2 border-yellow-500"
                                2 "bg-gray-700/50 border-2 border-gray-400"
                                3 "bg-orange-900/40 border-2 border-orange-700"
                                "bg-gray-800"))}
            [:div {:class "flex items-center gap-4"}
             (when medal
               [:span {:class "text-4xl"} medal])
             [:span {:class (str "text-2xl font-bold " (when (= rank 1) "text-yellow-300"))}
              name]]
            [:div {:class "text-3xl font-bold"}
             score [:span {:class "text-xl text-gray-400"} " / " max-score]]]))]]]))

(defn scoreboard-slide []
  [scores-display "Final Scores"])

(defn intermediate-scores-slide []
  [scores-display "Current Scores"])



;; >> Default Slide

(defn default-slide [slide-id]
  [slide-wrapper
   [:div {:class "text-center"}
    [:h1 {:class "text-6xl font-bold font-mono"} slide-id]]])



;; >> Slide Router

(defn render-slide [slide-id]
  (cond
    (= slide-id "title") [title-slide]
    (= slide-id "scoreboard") [scoreboard-slide]
    (= slide-id "scores") [intermediate-scores-slide]
    (presenter/question-id? slide-id) [question-slide slide-id]
    (presenter/results-id? slide-id) [analysis-slide (subs slide-id 0 (- (count slide-id) 8))]
    :else [default-slide slide-id]))

(defn display-ui []
  (let [slide-id (:slide-id @state)]
    [:div {:class "relative"}
     (if slide-id
       [render-slide slide-id]
       [slide-wrapper
        [:div {:class "text-2xl text-gray-500"} "Waiting for presenter..."]])
     [reactions-layer]]))



;; >> Init

(defn init! []

  ;; Generate QR code
  (-> (QRCode/toDataURL (str "http://" join-url) #js {:width 200 :margin 1})
      (.then #(swap! state assoc :qr-code-data %))
      (.catch #(println "QR code generation failed:" %)))

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Watch audience/players - track both count and player info
  (ably/on-presence-change! "audience"
    (fn []
      (ably/get-presence-members "audience"
        (fn [members]
          (let [players (reduce
                          (fn [acc member]
                            (let [client-id (.-clientId member)
                                  data (.-data member)]
                              (assoc acc client-id {:client-id client-id
                                                    :name (:name data)})))
                          {}
                          members)]
            (swap! state assoc
                   :audience-count (count members)
                   :players players))))))

  ;; Watch presenter state
  (presenter/on-state-change!
    (fn []
      (presenter/get-state
        (fn [presenter-state]
          (when presenter-state
            (swap! state assoc :slide-id (:slide-id presenter-state)))))))

  ;; Subscribe to votes
  (ably/subscribe! "votes" process-vote)

  ;; Subscribe to reactions
  (ably/subscribe! "reactions" process-reaction)

  ;; Subscribe to control channel for reset
  (ably/subscribe! "control" (fn [_] (reset-state!)))

  ;; Cleanup old reactions periodically (just to free memory)
  (js/setInterval cleanup-old-reactions! 5000))

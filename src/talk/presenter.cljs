(ns talk.presenter
  (:require ["./ably.js" :as ably]
            ["./ui.js" :as ui]))

;; >> Slides & Questions

;; Questions data - add/remove questions here, everything else is generated
(def questions
  [;; Round 1 - General Knowledge (Surprising Facts)
   {:text "Which country has MORE pyramids than Egypt?"
    :kind :choice
    :options ["Mexico" "Sudan" "Peru" "Iraq"]
    :answer "Sudan"}
   {:text "What can hippos NOT do, despite spending most of their time in water?"
    :kind :choice
    :options ["Hold their breath" "Open their eyes underwater" "Swim" "Float"]
    :answer "Swim"}
   {:text "The phrase 'saved by the bell' actually originated from which sport?"
    :kind :choice
    :options ["Wrestling" "Boxing" "Fencing" "Horse racing"]
    :answer "Boxing"}

   ;; Round 1 - Christmas (Weird Traditions)
   {:text "Based on biology, Santa's reindeer must all be what?"
    :kind :choice
    :options ["Under 5 years old" "Female" "Albino" "From Norway"]
    :answer "Female"}
   {:text "In which country do people roller-skate to Christmas morning church services?"
    :kind :choice
    :options ["Brazil" "Philippines" "Venezuela" "Colombia"]
    :answer "Venezuela"}
   {:text "In what year did KFC launch its Christmas dinner tradition in Japan?"
    :kind :scale
    :options {:min 1965 :max 1990}
    :answer 1974}

   ;; Round 1 - New Zealand (Unique Facts)
   {:text "Approximately how many years ago did humans first arrive in New Zealand?"
    :kind :scale
    :options {:min 500 :max 5000}
    :answer 800}
   {:text "In New Zealand's 2001 census, over 53,000 people listed what as their religion?"
    :kind :choice
    :options ["Pastafarian" "Jedi" "Sith" "Hobbit"]
    :answer "Jedi"}
   {:text "New Zealand officially appointed someone to what unusual government position in 1990?"
    :kind :choice
    :options ["Royal Jester" "Official Wizard" "Chief Hobbit" "Minister of Silly Walks"]
    :answer "Official Wizard"}
   {:text "How many islands make up New Zealand (not just the two main ones)?"
    :kind :scale
    :options {:min 50 :max 1000}
    :answer 600}

   ;; Round 2 - General Knowledge
   {:text "Which is larger in area: Central Park in NYC or the entire country of Monaco?"
    :kind :choice
    :options ["Central Park" "Monaco" "They're the same" "Depends on the tide"]
    :answer "Central Park"}
   {:text "What animal produces cube-shaped poop?"
    :kind :choice
    :options ["Capybara" "Wombat" "Koala" "Platypus"]
    :answer "Wombat"}
   {:text "How old is Jonathan the tortoise, the world's oldest known living land animal?"
    :kind :scale
    :options {:min 120 :max 250}
    :answer 192}

   ;; Round 2 - Christmas
   {:text "In Iceland, children who don't receive new clothes for Christmas risk being eaten by what?"
    :kind :choice
    :options ["The Yule Goat" "The Yule Cat" "Krampus" "The Snow Troll"]
    :answer "The Yule Cat"}
   {:text "At exactly 3pm on Christmas Eve, half of Sweden sits down to watch what on TV?"
    :kind :choice
    :options ["The Sound of Music" "Home Alone" "Donald Duck cartoons" "A Charlie Brown Christmas"]
    :answer "Donald Duck cartoons"}
   {:text "How many mischievous Yule Lads (troll-Santas) does Iceland have instead of one Santa?"
    :kind :scale
    :options {:min 5 :max 25}
    :answer 13}

   ;; Round 2 - New Zealand
   {:text "Which Kiwi may have achieved powered flight BEFORE the Wright Brothers in 1903?"
    :kind :choice
    :options ["Richard Pearse" "Ernest Rutherford" "Edmund Hillary" "Bill Hamilton"]
    :answer "Richard Pearse"}
   {:text "New Zealand has three official languages: English, Te Reo Māori, and what else?"
    :kind :choice
    :options ["Samoan" "Mandarin" "NZ Sign Language" "Cook Islands Māori"]
    :answer "NZ Sign Language"}
   {:text "Dunedin's Baldwin Street holds what Guinness World Record?"
    :kind :choice
    :options ["Narrowest street" "Steepest street" "Longest street name" "Most potholes"]
    :answer "Steepest street"}
   {:text "How many sheep are there in New Zealand for every person?"
    :kind :scale
    :options {:min 1 :max 15}
    :answer 5}

   ;; Round 3 - General Knowledge
   {:text "Tsutomu Yamaguchi survived both atomic bombs in WWII. Where was his hometown?"
    :kind :choice
    :options ["Hiroshima" "Nagasaki" "Tokyo" "Osaka"]
    :answer "Nagasaki"}
   {:text "What part of your body contains zero muscles?"
    :kind :choice
    :options ["Your ears" "Your nose" "Your fingers" "Your lips"]
    :answer "Your fingers"}
   {:text "How long can a sloth hold its breath underwater (longer than dolphins)?"
    :kind :scale
    :options {:min 5 :max 60}
    :answer 40}

   ;; Round 3 - Christmas
   {:text "In Norway, families hide what household item on Christmas Eve to stop witches stealing it?"
    :kind :choice
    :options ["Knives" "Brooms" "Candles" "Mirrors"]
    :answer "Brooms"}
   {:text "In Ukraine, it's traditional to decorate Christmas trees with what creepy decoration?"
    :kind :choice
    :options ["Fake spider webs" "Miniature coffins" "Black cats" "Skulls"]
    :answer "Fake spider webs"}
   {:text "In what year was William the Conqueror's coronation - marked by an accidental massacre?"
    :kind :scale
    :options {:min 1000 :max 1200}
    :answer 1066}

   ;; Round 3 - New Zealand
   {:text "New Zealand invented what common everyday item in the 1950s?"
    :kind :choice
    :options ["Velcro" "Disposable syringe" "Post-it notes" "Bubble wrap"]
    :answer "Disposable syringe"}
   {:text "Which New Zealand running coach is credited with inventing 'jogging' as exercise?"
    :kind :choice
    :options ["Peter Snell" "Arthur Lydiard" "John Walker" "Jack Lovelock"]
    :answer "Arthur Lydiard"}
   {:text "How many characters are in the longest place name in any English-speaking country (a NZ hill)?"
    :kind :scale
    :options {:min 40 :max 100}
    :answer 85}
   {:text "The Pōhutukawa tree, which blooms in December, is known as New Zealand's what?"
    :kind :choice
    :options ["National Tree" "Christmas Tree" "Wedding Tree" "Sacred Tree"]
    :answer "Christmas Tree"}

   ;; Round 4 - General Knowledge
   {:text "What color is a polar bear's skin (under the white fur)?"
    :kind :choice
    :options ["Pink" "White" "Black" "Grey"]
    :answer "Black"}
   {:text "Wind is actually completely silent. What makes the sound we call 'wind'?"
    :kind :choice
    :options ["Air molecules colliding" "Wind hitting objects" "Temperature changes" "Pressure waves"]
    :answer "Wind hitting objects"}
   {:text "How much does an average cumulus cloud weigh in tonnes?"
    :kind :scale
    :options {:min 100 :max 2000000}
    :answer 1000000}

   ;; Round 4 - Christmas
   {:text "What's the name of the fake gangster movie Kevin watches in Home Alone?"
    :kind :choice
    :options ["Scarface 2" "Angels with Filthy Souls" "Goodfellas Jr." "The Chicago Way"]
    :answer "Angels with Filthy Souls"}
   {:text "In which country do nativity scenes include 'caganers' - figures doing their business with pants down?"
    :kind :choice
    :options ["Italy" "Catalonia/Spain" "Portugal" "Mexico"]
    :answer "Catalonia/Spain"}
   {:text "The song '12 Days of Christmas' includes how many total gifts if you count all repetitions?"
    :kind :scale
    :options {:min 50 :max 400}
    :answer 364}

   ;; Round 4 - New Zealand
   {:text "Which extinct giant bird of New Zealand could grow up to 3.6 meters tall?"
    :kind :choice
    :options ["Haast's Eagle" "Moa" "Giant Kiwi" "Pouakai"]
    :answer "Moa"}
   {:text "New Zealand has more of these per capita than Scotland does. What are they?"
    :kind :choice
    :options ["Whisky distilleries" "Scottish piping bands" "Tartan shops" "Haggis restaurants"]
    :answer "Scottish piping bands"}
   {:text "On which days is ALL advertising banned from NZ TV and radio?"
    :kind :choice
    :options ["ANZAC Day only" "Christmas, Good Friday, Easter Sunday" "Every Sunday" "Queen's Birthday"]
    :answer "Christmas, Good Friday, Easter Sunday"}
   {:text "New Zealand was the first country to give women the vote (1893). How many years before the UK?"
    :kind :scale
    :options {:min 10 :max 50}
    :answer 25}

   ;; Round 5 - General Knowledge
   {:text "According to the Bible, how many wise men visited baby Jesus?"
    :kind :choice
    :options ["Three" "Four" "Twelve" "It doesn't say"]
    :answer "It doesn't say"}
   {:text "Which car company owns Lamborghini, Bentley, Bugatti, AND Porsche?"
    :kind :choice
    :options ["BMW Group" "Mercedes-Benz" "Volkswagen Group" "Stellantis"]
    :answer "Volkswagen Group"}
   {:text "How many bones does a shark have?"
    :kind :scale
    :options {:min 0 :max 300}
    :answer 0}

   ;; Round 5 - Christmas
   {:text "In Sweden's town of Gävle, a giant straw goat is built each Christmas. What usually happens to it?"
    :kind :choice
    :options ["It's sailed down river" "Vandals burn it down" "Children ride it" "It's eaten by goats"]
    :answer "Vandals burn it down"}
   {:text "Which singer joined Bing Crosby for 'The Little Drummer Boy' duet in a 1977 TV special?"
    :kind :choice
    :options ["Elvis Presley" "Frank Sinatra" "David Bowie" "John Lennon"]
    :answer "David Bowie"}
   {:text "In what year did 'Jingle Bells' become the first song broadcast from space?"
    :kind :scale
    :options {:min 1960 :max 1975}
    :answer 1965}

   ;; Round 5 - New Zealand
   {:text "What percentage of New Zealand's electricity comes from renewable sources?"
    :kind :choice
    :options ["40%" "60%" "80%" "95%"]
    :answer "80%"}
   {:text "Where is the world's first International Dark Sky Reserve (for stargazing)?"
    :kind :choice
    :options ["Chilean Andes" "Mackenzie Region, NZ" "Sahara Desert" "Iceland"]
    :answer "Mackenzie Region, NZ"}
   {:text "What was the first fast-food chain to open in New Zealand (1971)?"
    :kind :choice
    :options ["McDonald's" "Burger King" "KFC" "Pizza Hut"]
    :answer "KFC"}
   {:text "How many of New Zealand's native bird species are flightless?"
    :kind :scale
    :options {:min 5 :max 25}
    :answer 16}])

;; Generate IDs and lookup map from questions vector
(def question-ids (mapv #(str "q" (inc %)) (range (count questions))))

(def questions-by-id
  (into {} (map-indexed (fn [i q] [(str "q" (inc i)) (assoc q :id (str "q" (inc i)))]) questions)))

;; Generate slides from questions (with score breaks every 10 questions)
(def slides
  (vec (concat ["title"]
               (mapcat (fn [i qid]
                         (let [base [[qid (str qid "-results")]]
                               ;; Add scores slide after every 10 questions (but not at the end)
                               needs-break? (and (pos? (inc i))
                                                 (zero? (mod (inc i) 10))
                                                 (< (inc i) (count question-ids)))]
                           (if needs-break?
                             (conj base "scores")
                             base)))
                       (range)
                       question-ids)
               ["scoreboard"])))

(def slide-ids (->> slides
                    (map #(if (string? %) [%] %))
                    (apply concat)
                    vec))

(def notes {})

(defn get-question [question-id]
  (get questions-by-id question-id))

(defn question-id? [slide-id]
  (contains? questions-by-id slide-id))

(defn results-id? [slide-id]
  (and (string? slide-id)
       (.endsWith slide-id "-results")
       (question-id? (subs slide-id 0 (- (count slide-id) 8)))))


;; >> State

(def default-state {:slide-id (first slide-ids)
                    :audience-count 0
                    :selected-speaker nil
                    :audience-members []})

(defn save-state! []
  (js/localStorage.setItem "presenter-state"
    (js/JSON.stringify (select-keys @state [:slide-id :selected-speaker]))))

(defn load-state []
  (when-let [saved (js/localStorage.getItem "presenter-state")]
    (let [parsed (js/JSON.parse saved)]
      {:slide-id (or (:slide-id parsed) (first slide-ids))
       :selected-speaker (:selected-speaker parsed)})))

(def state (atom (merge default-state (load-state))))

(def CHANNEL "presenter")



;; >> Presence Helpers

(defn get-state [callback]
  (ably/get-presence-members CHANNEL
    (fn [members]
      (callback (when (seq members) (.-data (first members)))))))

(defn on-state-change! [callback]
  (ably/on-presence-change! CHANNEL callback))

;; >> Sync state to presenter channel

(defn sync-state! []
  (save-state!)
  (ably/update-presence! CHANNEL {:slide-id (:slide-id @state)
                                   :selected-speaker (:selected-speaker @state)}))



;; >> Speaker Selection

(defn select-random-speaker! []
  (let [members (:audience-members @state)]
    (when (seq members)
      (let [speaker (rand-nth members)]
        (swap! state assoc :selected-speaker speaker)
        (sync-state!)))))

(defn clear-speaker-messages! []
  (ably/publish! "speaker" "message" {:command "clear"}))

(defn speaker-present? []
  (let [speaker (:selected-speaker @state)
        members (:audience-members @state)]
    (and speaker (some #(= % speaker) members))))



;; >> Control Actions

(defn current-slide-index []
  (let [current (:slide-id @state)]
    (or (.indexOf slide-ids current) 0)))

(defn next-slide! []
  (let [idx (current-slide-index)
        next-idx (min (dec (count slide-ids)) (inc idx))]
    (swap! state assoc :slide-id (nth slide-ids next-idx))
    (sync-state!)))

(defn prev-slide! []
  (let [idx (current-slide-index)
        prev-idx (max 0 (dec idx))]
    (swap! state assoc :slide-id (nth slide-ids prev-idx))
    (sync-state!)))

(defn go-to-slide! [slide-id]
  (swap! state assoc :slide-id slide-id)
  (sync-state!))

(defn reset-state! []
  (js/localStorage.removeItem "presenter-state")
  (swap! state assoc :slide-id (first slide-ids) :selected-speaker nil)
  (sync-state!)
  ;; Broadcast reset to all clients
  (ably/publish! "control" "reset" {:timestamp (js/Date.now)}))



;; >> UI Components

(defn button [attrs & children]
  (into [:button (merge {:class "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 active:bg-blue-800 disabled:bg-gray-400"}
                        attrs)]
        children))

(defn slide-button [slide-id disabled?]
  (let [current? (= slide-id (:slide-id @state))]
    [button {:class (if current?
                      "px-4 py-2 bg-green-600 text-white rounded-lg disabled:bg-gray-400"
                      "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-gray-400")
             :on-click #(go-to-slide! slide-id)
             :disabled disabled?}
     slide-id]))

(defn speaker-selection-ui [disabled?]
  (let [speaker (:selected-speaker @state)
        present? (speaker-present?)]
    [:div {:class "p-3 bg-purple-900/50 border border-purple-700 rounded-lg space-y-2"}
     [:h2 {:class "text-sm font-semibold text-purple-400"} "Selected Speaker"]
     (if speaker
       [:div {:class "flex items-center gap-2"}
        [:span {:class (if present? "text-green-400" "text-red-400")} "●"]
        [:span {:class "font-mono text-sm truncate flex-1"} speaker]
        [:span {:class "text-xs text-gray-400"} (if present? "online" "offline")]]
       [:div {:class "text-gray-400 text-sm"} "No speaker selected"])
     [:div {:class "flex gap-2"}
      [button {:on-click select-random-speaker!
               :disabled disabled?
               :class "px-3 py-1 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 disabled:bg-gray-600"}
       (if speaker "Re-roll" "Select Random")]
      [button {:on-click clear-speaker-messages!
               :disabled disabled?
               :class "px-3 py-1 bg-gray-600 text-white text-sm rounded-lg hover:bg-gray-500 disabled:bg-gray-700"}
       "Clear Messages"]]]))

(defn presenter-ui []
  (let [disabled? (not (ably/connected?))
        current-slide (:slide-id @state)
        idx (current-slide-index)]
    [:div {:class "min-h-screen bg-gray-900 text-white"}
     [:div {:class "p-4 space-y-6 max-w-md mx-auto"}
      [:h1 {:class "text-2xl font-bold"} "Quiz Controls"]

      [:div {:class "text-lg"}
       "Participants: " (:audience-count @state)]

      [:div {:class "space-y-2"}
       [:h2 {:class "text-xl font-semibold"} "Current Slide"]
       [:div {:class "flex gap-2 items-center"}
        [button {:on-click prev-slide! :disabled disabled?} "Prev"]
        [button {:on-click next-slide! :disabled disabled?} "Next"]
        [:span {:class "px-4 py-2 font-mono text-lg"} current-slide]
        [:span {:class "text-gray-400"} "(" (inc idx) "/" (count slide-ids) ")"]]]

      (when-let [slide-notes (get notes current-slide)]
        [:div {:class "p-3 bg-yellow-900/50 border border-yellow-700 rounded-lg"}
         [:h2 {:class "text-sm font-semibold text-yellow-400 mb-2"} "Notes"]
         [:div {:class "space-y-1"}
          (for [[idx note] (map-indexed vector slide-notes)]
            ^{:key idx}
            [:p {:class "text-yellow-200"} note])]])

      [:div {:class "space-y-2"}
       [:h2 {:class "text-xl font-semibold"} "All Slides"]
       [:div {:class "flex flex-col gap-2"}
        (for [[idx item] (map-indexed vector slides)]
          ^{:key idx}
          (if (string? item)
            [slide-button item disabled?]
            [:div {:class "flex gap-2"}
             (for [sid item]
               ^{:key sid}
               [slide-button sid disabled?])]))]]

      [:div {:class "pt-4 border-t border-gray-700"}
       [button {:class "px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:bg-gray-600"
                :on-click #(when (js/confirm "Reset all state for presenter, display, and audience?")
                             (reset-state!))
                :disabled disabled?}
        "Reset"]]

      [ui/connection-pill]]]))



;; >> Init

(defn init! []

  ;; Re-render when ably connection status changes
  (add-watch ably/state ::connection
    (fn [_ _ _ _]
      (swap! state identity)))

  ;; Enter presenter presence with initial state
  (ably/enter-presence! CHANNEL {:slide-id (:slide-id @state)})

  ;; Watch audience members
  (ably/on-presence-change! "audience"
    (fn []
      (ably/get-presence-members "audience"
        (fn [members]
          (let [member-ids (mapv #(.-clientId %) members)]
            (swap! state assoc
                   :audience-count (count members)
                   :audience-members member-ids)))))))

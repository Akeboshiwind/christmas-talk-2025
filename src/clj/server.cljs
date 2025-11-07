(ns server)

(defn file [path content-type]
  (js/Response. (.file js/Bun path) {:headers {"Content-Type" content-type}}))

(defn sse [stream]
  (js/Response.
    stream
    {:headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"}}))

(defn event [msg]
  (if (string? msg)
    (str "data: " msg "\n\n")
    (let [{:keys [event data id retry]} msg]
      (str (when event (str "event: " event "\n"))
           (when data (str "data: " data "\n"))
           (when id (str "id: " id "\n"))
           (when retry (str "retry: " retry "\n"))
           "\n\n"))))

(def counter (atom 0))

(def clients (atom #{}))

(defn broadcast [msg]
  (let [e (event msg)]
    (doseq [client @clients]
      (.enqueue client e))))

(defn events [req]
  (let [_last-id (.. req -headers (get "last-event-id"))
        client (atom nil)
        keepalive (atom nil)]
    (println "SSE connection requested, last-event-id:" _last-id)
    (js/ReadableStream.
      {:start (fn [controller]
                (println "New SSE client connected")
                (reset! client controller)
                (swap! clients conj controller)
                (.enqueue controller (event {:event "counter" :data @counter}))
                (reset! keepalive
                        (js/setInterval
                           #(.enqueue controller (event {:event "ping"}))
                           30000)))
       :cancel #(do (println "SSE client disconnected")
                    (swap! clients disj @client)
                    (js/clearInterval @keepalive))})))

(defn handler [req]
  (let [url (js/URL. (.-url req))
        pathname (.-pathname url)]
    (condp re-matches pathname
      #"/" (file "public/index.html" "text/html")
      #"/app\.js" (file "app.js" "application/javascript")
      #"/node_modules/.*" (file (str ".." pathname) "application/javascript")
      #"/api/sse" (sse (events req))
      #"/api/counter/inc"
      (do (swap! counter inc)
          (broadcast {:event "counter" :data @counter})
          (js/Response. "OK" {:status 200}))
      #"/api/counter/dec"
      (do (swap! counter dec)
          (broadcast {:event "counter" :data @counter})
          (js/Response. "OK" {:status 200}))
      (js/Response. "Not Found" {:status 404}))))

(def server (.serve js/Bun {:port 3000 :idleTimeout 40 :fetch handler}))

(.log js/console (str "Server running at http://localhost:" (.-port server)))

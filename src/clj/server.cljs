(ns server)

(defn file [path content-type]
  (js/Response. (.file js/Bun path) {:headers {"Content-Type" content-type}}))

(defn sse [req async-gen]
  (js/Response.
    (^{:gen true :async true} fn [] (js-yield* (async-gen req)))
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

(defn ^{:gen true :async true} events [req]
  (let [last-id (.. req -headers (get "last-event-id"))
        start (or (some-> last-id js/parseInt inc) 1)]
    (js-yield (event "Hello World!"))
    (loop [i start]
      (js/await (Bun.sleep 1000))
      (js-yield (event {:data (str i) :id (str i)}))
      (recur (inc i)))))

(defn handler [req]
  (let [url (js/URL. (.-url req))
        pathname (.-pathname url)]
    (condp re-matches pathname
      #"/" (file "public/index.html" "text/html")
      #"/app\.js" (file "app.js" "application/javascript")
      #"/node_modules/.*" (file (str ".." pathname) "application/javascript")
      #"/api/sse" (sse req events)
      (js/Response. "Not Found" #js {:status 404}))))

(def server (.serve js/Bun {:port 3000 :fetch handler}))

(.log js/console (str "Server running at http://localhost:" (.-port server)))

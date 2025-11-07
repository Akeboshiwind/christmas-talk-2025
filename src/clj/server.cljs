(ns server
  (:require ["node:http2" :as http2]
            ["node:fs" :as fs]))

(defn event [msg]
  (if (string? msg)
    (str "data: " msg "\n\n")
    (let [{:keys [event data id retry]} msg]
      (str (when event (str "event: " event "\n"))
           (when data (str "data: " data "\n"))
           (when id (str "id: " id "\n"))
           (when retry (str "retry: " retry "\n"))
           "\n\n"))))

(defn broadcast [clients msg]
  (let [e (event msg)]
    (doseq [client clients]
      (.write client e))))

(defn setup-sse [stream on-start on-close]
  (.respond stream {":status" 200
                    "content-type" "text/event-stream"
                    "cache-control" "no-cache"})
  (let [keepalive (atom nil)]
    (on-start stream)
    (reset! keepalive
            (js/setInterval
              #(.write stream (event {:event "ping"}))
              30000))
    (.on stream "close"
         #(do (on-close stream)
              (js/clearInterval @keepalive)))))

(defn respond! [stream {:keys [status headers body]}]
  (.respond stream (merge {":status" status} headers))
  (.end stream body))

(defn respond-file! [stream {:keys [status headers path]}]
  (.respondWithFile stream path (merge {":status" status} headers)))

(defn not-found []
  {:status 404
   :content-type "text/plain"
   :body "Not Found"})

(defn file [path content-type]
  {:status 200
   :headers {"content-type" content-type}
   :path path})

(def counter (atom 0))
(def clients (atom #{}))

(defn handle-counter [f]
  (swap! counter f)
  (broadcast @clients {:event "counter" :data @counter})
  {:status 200 :headers {"content-type" "text/plain"} :body "OK"})

(defn handler [stream headers]
  (let [pathname (aget headers ":path")]
    (condp re-matches pathname
      ;; >> Static Files
      #"/" (respond-file! stream (file "public/index.html" "text/html"))
      #"/app\.js" (respond-file! stream (file "app.js" "application/javascript"))
      #"/node_modules/.*" (respond-file! stream (file (str ".." pathname) "application/javascript"))

      ;; >> API
      #"/api/sse"
      (setup-sse stream
        (fn on-start [stream]
          (println "New SSE client connected")
          (swap! clients conj stream)
          (.write stream (event {:event "counter" :data @counter})))
        (fn on-close [stream]
          (println "SSE client disconnected")
          (swap! clients disj stream)))
      #"/api/counter/inc" (respond! stream (handle-counter inc))
      #"/api/counter/dec" (respond! stream (handle-counter dec))

      ;; >> Default
      (respond! stream (not-found)))))

(def server
  (.createSecureServer
    http2
    {:key (.readFileSync fs "../localhost-key.pem")
     :cert (.readFileSync fs "../localhost-cert.pem")
     :timeout 40000}))

(.on server "stream" handler)

(.listen server 3000 #(println "Server running at https://localhost:3000"))

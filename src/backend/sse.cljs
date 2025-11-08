(ns sse)

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

(defn setup! [stream on-start on-close]
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

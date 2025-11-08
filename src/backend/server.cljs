(ns server
  (:require ["./http" :as http]
            ["./sse" :as sse]
            ["node:fs" :as fs]))

(def counter (atom 0))
(def clients (atom #{}))

(defn content-type [path]
  (cond
    (re-find #"\.html$" path) "text/html"
    (re-find #"\.js$" path) "application/javascript"
    (re-find #"\.css$" path) "text/css"
    (re-find #"\.map$" path) "application/json"
    :else "application/octet-stream"))

(defn handle-counter [f]
  (swap! counter f)
  (sse/broadcast @clients {:event "counter" :data @counter})
  {:status 200 :headers {"content-type" "text/plain"} :body "OK"})

(def index
  (str #html [:<>
              [:$ "<!DOCTYPE html>"]
              [:html {:lang "en"}
               [:head
                [:meta {:charset "UTF-8"}]
                [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                [:title "Squint App"]
                [:link {:rel "stylesheet" :href "/app.css"}]]
               [:body
                [:div {:id "app"}]
                [:script {:type "module" :src "/app.js"}]]]]))

(defn handler [stream headers]
  (let [pathname (aget headers ":path")]
    (condp re-matches pathname
      ;; >> API
      #"/api/sse"
      (sse/setup! stream
        (fn on-start [stream]
          (println "New SSE client connected")
          (swap! clients conj stream)
          (.write stream (sse/event {:event "counter" :data @counter})))
        (fn on-close [stream]
          (println "SSE client disconnected")
          (swap! clients disj stream)))

      #"/api/counter/inc" (http/respond! stream (handle-counter inc))
      #"/api/counter/dec" (http/respond! stream (handle-counter dec))

      ;; >> Static Files
      #"/" (http/respond! stream (-> (http/response index) (http/content-type "text/html")))
      #"/[^/]+" (http/respond-file! stream (http/file (str "public" pathname) (content-type pathname)))

      ;; >> Default
      (http/respond! stream (http/not-found "Not Found")))))

(http/run-server handler
                 {:key (.readFileSync fs "../localhost-key.pem")
                  :cert (.readFileSync fs "../localhost-cert.pem")})

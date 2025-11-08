(ns http
  (:require ["node:http2" :as http2]))

(defn respond! [stream {:keys [status headers body]}]
  (.respond stream (merge {":status" status} headers))
  (.end stream body))

(defn respond-file! [stream {:keys [status headers path]}]
  (.respondWithFile stream path (merge {":status" status} headers)))

(defn response [body]
  {:status 200
   :body body})

(defn not-found [body]
  {:status 404
   :body body})

(defn content-type [resp content-type]
  (assoc-in resp [:headers "Content-Type"] content-type))

(defn file [path content-type]
  {:status 200
   :headers {"Content-Type" content-type}
   :path path})

(defn run-server [handler opts]
  (let [{:keys [port]
         :or {port 3000}} opts
        s (.createSecureServer http2 opts)]
    (.on s "stream" handler)
    (.listen s port #(println (str "Server running at https://localhost:" port)))))

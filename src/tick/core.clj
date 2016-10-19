;; Copyright © 2016, JUXT LTD.

(ns tick.core
  (:require
   [clojure.spec :as s])
  (:import
   [java.time Clock ZoneId Instant Duration DayOfWeek Month ZonedDateTime]
   [java.time.temporal ChronoUnit]
   [java.util.concurrent TimeUnit]))

(defn clock []
  (Clock/systemDefaultZone))

(defn clock-ticking-in-seconds []
  (Clock/tickSeconds (ZoneId/systemDefault)))

(defn fixed-clock [^ZonedDateTime zdt]
  (Clock/fixed (.toInstant zdt) (.getZone zdt)))

(defn nanos [n]
  (Duration/ofNanos n))

(defn millis [n]
  (Duration/ofMillis n))

(defn seconds [n]
  (Duration/ofSeconds n))

(defn minutes [n]
  (Duration/ofMinutes n))

(defn hours [n]
  (Duration/ofHours n))

(defn days [n]
  (Duration/ofDays n))

(defn parse
  "Given a string in ISO-8601 format, parse to an java.time.Instant."
  ([s]
   (Instant/parse s))
  ([s z]
   (-> (parse s) (.atZone (ZoneId/of z)))))

(defn periodic-seq
  "Given a clock and a duration, create an infinite sequence of
  ZonedDateTime instances starting with the clock's instant and
  advancing by the given period."
  ([^Clock c ^Duration period]
   (let [zone (.getZone c)]
     (->> (iterate #(.addTo period %) (.instant c))
          (map #(.atZone % zone))))))

(defn day-of-week
  "Return the day of the week for a given ZonedDateTime"
  [zdt]
  (.getDayOfWeek zdt))

(defn weekend?
  "Is the ZonedDateTime during the weekend?"
  [zdt]
  (#{DayOfWeek/SATURDAY DayOfWeek/SUNDAY} (day-of-week zdt)))

(defn easter-sunday? [zdt]
  "Copyright © 2016 Eivind Waaler. EPL v1.0. Given a ZoneId, return a
  predicate that tests is the instant falls on an Easter Sunday. From
  https://github.com/eivindw/clj-easter-day, using Spencer Jones
  formula."
  (let [year (.getYear zdt)
        month (.getMonthValue zdt)]
    (and
     (= (day-of-week zdt) DayOfWeek/SUNDAY)
     (or (= month 3) (= month 4))
     (let [a (mod year 19)
           b (quot year 100)
           c (mod year 100)
           d (quot b 4)
           e (mod b 4)
           f (quot (+ b 8) 25)
           g (quot (+ (- b f) 1) 3)
           h (mod (+ (* 19 a) (- b d g) 15) 30)
           i (quot c 4)
           k (mod c 4)
           l (mod (- (+ 32 (* 2 e) (* 2 i)) h k) 7)
           m (quot (+ a (* 11 h) (* 22 l)) 451)
           n (quot (+ h (- l (* 7 m)) 114) 31)
           p (mod (+ h (- l (* 7 m)) 114) 31)]
       (and (= n month) (= (.getDayOfMonth zdt) (+ p 1)))))))

(defn good-friday? [zdt]
  (easter-sunday? (.plus zdt (days 2))))

(defn easter-monday? [zdt]
  (easter-sunday? (.minus zdt (days 1))))

;; TODO: rename?
(defn drainer
  "Call sinkf with each past event. Return future events"
  [^Clock clock sinkf]
  (fn [times]
    (let [now (.instant clock)]
      (loop [tms times]
        (if-not (.isAfter (.toInstant (first tms)) now)
          (do
            (sinkf (first tms))
            (recur (rest tms)))
          tms)))))

;; Scheduler

;; An atom containing a (potentially ticking) clock.
;; Functions to replace the clock, or programmatically tick a fixed clock
;; A scheduled-future registered with a ScheduledThreadPoolExecutor, which gets cancelled on any change to a clock
;; A function to call with a ZDT as an argument
;;

(defn callback [a]
  (fn []
    ;; Find past/future times
    ;; Reschedule (update atom)

    (let [{:tick/keys [clock future-timeline callable executor]} (deref a)
          ;; We capture a little bit ahead of ourselves, to avoid an unnecessary reschedule.
          ;; TODO: This may not be necessary
          ;; now (.minus (.instant clock) (millis 2))
          now (.instant clock)
          ]
      (println "TICK")
      (try
        (let [[due next-future-timeline] (split-with (fn [zdt] (not (.isAfter (.toInstant zdt) now))) future-timeline)]

          (swap! a assoc :tick/future-timeline next-future-timeline)

          (when-let [ff (first next-future-timeline)]
            (let [dly (.until (.instant clock)
                              ff
                              ChronoUnit/MILLIS)]
              (.schedule executor ^Callable (callback a) dly TimeUnit/MILLISECONDS)))

          ;; dorun map our callable on each of the past times
          (dorun (map callable due)))

        (catch Exception e
          (println "ERROR" e))))))

(defn new-clock-tracker [^java.time.Clock clock timeline callable executor]
  (let [a
        (atom {:tick/future-timeline timeline
               :tick/clock clock
               :tick/callable callable
               :tick/executor executor})]

    (when true ;; clock is moving, we schedule the 'next' for real
      (let [dly (.until (.instant clock)
                        (first timeline)
                        ChronoUnit/MILLIS)]

        (println "Delay by " dly + "ms")

        ;; Figure out the time between the clock and (first timeline)
        (.schedule executor
                   ^Callable (callback a) dly TimeUnit/MILLISECONDS)))
    ;; Add watch to retrigger
    ;;(add-watch a )

    a
    ))

(defn- merge-timelines
  "Merge sort across set of collections.
   See http://blog.malcolmsparks.com/?p=42 for full details."
  ([^java.util.Comparator comp colls]
   (let [begin (new Object)
         end (new Object)]
     (letfn [(next-item [[_ colls]]
               (if (nil? colls)
                 [end nil]
                 (let [[[yield & p] & q]
                       (sort-by first comp colls)]
                   [yield (if p (cons p q) q)])))]
       (->> colls
            (vector begin)
            (iterate next-item)
            (drop 1)
            (map first)
            (take-while (partial not= end))))))
  ([colls]
   (merge-sort compare colls)))

(ns
  ^{:author ftomassetti}
  civs.logic.tribe-choices
  (:require
    [civs.model :refer :all]
    [civs.logic.basic :refer :all]
    [civs.logic.demographics :refer :all]
    [civs.society :refer :all])
  (:import [civs.model Population Tribe]))

(defn chance-to-become-semi-sedentary [game tribe]
  (let [ world (.world game)
         prosperity (prosperity game tribe)]
    (if (and (nomadic? tribe) (> prosperity 0.9)) 0.05 0.0)))

; Must be at least a tribe society
(defn chance-to-develop-agriculture [game tribe]
  (let [world (.world game)
         prosperity (prosperity game tribe)
         ss (semi-sedentary? tribe)
        know-agriculture (know? tribe :agriculture)]
    (if (and
          ss
          (not know-agriculture)
          (not (band-society? tribe))) 0.1 0.0)))

; Must be at least a tribe society
(defn chance-to-become-sedentary [game tribe]
  (let [world (.world game)
         prosperity (prosperity game tribe)
         ss (semi-sedentary? tribe)
        know-agriculture (know? tribe :agriculture)]
    (if (and
          ss
          know-agriculture
          (not (band-society? tribe))) 0.1 0.0)))

(defrecord PossibleEvent [name chance apply])

(def become-semi-sedentary
  (PossibleEvent.
    :become-semi-sedentary
    chance-to-become-semi-sedentary
    (fn [game tribe]
      (let [new-culture (assoc (.culture tribe) :nomadism :semi-sedentary)]
        {
          :tribe (assoc tribe :culture new-culture)
          :params {}
          :msg "became semi-sedentary"
          }))))

(def discover-agriculture
  (PossibleEvent.
    :discover-agriculture
    chance-to-develop-agriculture
    (fn [game tribe]
      (let [new-culture (assoc (.culture tribe) :nomadism :sedentary)]
        {
          :tribe (learn tribe :agriculture)
          :params {}
          :msg "discover agriculture"
          }))))

(def become-sedentary
  (PossibleEvent.
    :become-sedentary
    chance-to-become-sedentary
    (fn [game tribe]
      (let [ pos (.position tribe)
             new-culture (assoc (.culture tribe) :nomadism :sedentary)]
        {
          :game (:game (create-town game :unnamed pos (:id tribe)))
          :tribe (assoc tribe :culture new-culture)
          :params {}
          :msg "became sedentary"
          }))))

(def migrate
  (PossibleEvent.
    :migrate
    (fn [game tribe]
      (case
        (sedentary? tribe) 0
        (semi-sedentary? tribe) 0.15
        (nomadic? tribe) 0.85))
    (fn [game tribe]
      (let [ world (.world game)
             pos (.position tribe)
             _ (check-valid-position world pos)
             possible-destinations (land-cells-around world pos 3)
             preferences (map (fn [pos] {
                                     :preference (perturbate-low (prosperity-in-pos game tribe pos))
                                     :pos pos
                                     }) possible-destinations)
             preferences (sort-by :preference preferences)
             target (:pos (first preferences))]
        {
          :tribe (assoc tribe :position target)
          :params {:to target}
          :msg "migrate"
          }))))

(defn- split-pop [population]
  (let [[rc,lc] (rsplit-by (.children population) 0.4)
        [rym,lym] (rsplit-by (.young-men population) 0.4)
        [ryw,lyw] (rsplit-by (.young-women population) 0.4)
        [rom,lom] (rsplit-by (.old-men population) 0.4)
        [row,low] (rsplit-by (.old-women population) 0.4)]
    {:remaining (Population. rc rym ryw rom row) :leaving (Population. lc lym lyw lom low)}))

(def split
  (PossibleEvent.
    :split
    (fn [game tribe]
      (let [c (crowding (.world game) tribe (.position tribe))
            pop (-> tribe .population total-persons)]
        (if
          (and (> pop 30) (< c 0.9))
          (/ (opposite c) 2.0)
          0.0)))
    (fn [game tribe]
      (let [ world (.world game)
             pos (.position tribe)
             sp (split-pop (.population tribe))
             possible-destinations (land-cells-around world pos 3)
             preferences (map (fn [pos] {
                                          :preference (perturbate-low (prosperity-in-pos game tribe pos))
                                          :pos pos
                                          }) possible-destinations)
             preferences (sort-by :preference preferences)
             dest-target (:pos (first preferences))
             res (create-tribe game :unnamed dest-target (:leaving sp) (.culture tribe) (.society tribe))
             game (:game res)
             game (:game (create-town game :unnamed dest-target (:id (:tribe res))))]
        {
          :game game
          :tribe (assoc tribe :population (:remaining sp))
          :params {}
          :msg "split"
          }))))

(def evolution-in-tribe
  (PossibleEvent.
    :evolve-in-tribe
    (fn [game tribe]
      (possibility-of-evolving-into-tribe tribe))
    (fn [game tribe]
      {
        :tribe (evolve-in-tribe tribe)
        :params {}
        :msg "evolve in tribe"
        })))

(def evolution-in-chiefdom
  (PossibleEvent.
    :evolve-in-chiefdom
    (fn [game tribe]
      (possibility-of-evolving-into-chiefdom tribe))
    (fn [game tribe]
      {
        :tribe (evolve-in-chiefdom tribe)
        :params {}
        :msg "evolve in chiefdom"
        })))

(defn consider-event [game tribe event]
  "Return a map of game and tribe, changed"
  (let [p ((.chance event) game tribe)]
    (if (roll p)
      (let [apply-res ((.apply event) game tribe)
            new-tribe (:tribe apply-res)
            new-game (or (:game apply-res) game)
            new-game (update-tribe new-game new-tribe)
            params (assoc (:params apply-res) :tribe new-tribe)
            msg (:msg apply-res)]
        (fact (:name event) params msg)
        {:game new-game :tribe new-tribe})
      {:game game :tribe tribe} )))

(defn consider-events
  "Return a map of game and tribe, changed"
  [game tribe events]
  (if (empty? events)
    {:game game :tribe tribe}
    (let [e (first events)
          re (rest events)
          res (consider-event game tribe e)]
      (consider-events (:game res) (:tribe res) re))))

(defn consider-all-events
  "Return a map of game and tribe, changed"
  [game tribe]
  (consider-events game tribe [become-semi-sedentary discover-agriculture become-sedentary migrate split evolution-in-tribe evolution-in-chiefdom]))

(defn tribe-turn
  "Return the game, updated"
  [game tribe]
  (let [ world (.world game)
         tribe (update-population game tribe)
        game (update-tribe game tribe)]
    (:game (consider-all-events game tribe))))

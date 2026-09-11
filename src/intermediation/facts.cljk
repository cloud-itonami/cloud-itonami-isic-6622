(ns intermediation.facts
  "Per-jurisdiction insurance-intermediation licensing/commission-cap
  catalog -- the G2-style spec-basis table the Insurance Intermediation
  Governor checks every jurisdiction/assess proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's broker-
  licensing/commission-disclosure requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-isic-6511`'s `underwriting.facts` / `cloud-itonami-isic-
  6512`'s `casualty.facts` / `cloud-itonami-isic-6621`'s
  `adjustment.facts` use: a jurisdiction not in this table has NO
  spec-basis, full stop -- the advisor must not fabricate one, and the
  governor holds if it tries.

  `:commission-rate-cap` is an illustrative ceiling, the same 'a common,
  NOT universal rate, a real engagement's actual cap always governs'
  posture `vcfund.nav/default-management-fee-rate` and `kotoba-lang`'s
  other default-rate constants already take -- never treated as an
  authoritative regulatory maximum for every product line in that
  jurisdiction.

  Seed values are drawn from each jurisdiction's official insurance-
  intermediation regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions. Extending
  coverage is additive: add one map to `catalog`, cite a real source,
  done -- never invent a jurisdiction's requirements to make coverage
  look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-docs` mirrors the generic
  intermediation checklist a broker/agent submits in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit; `:commission-rate-cap` is the illustrative
  ceiling (see ns docstring)."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act) -- 保険仲立人・保険代理店制度"
          :national-spec "日本損害保険協会/生命保険協会 代理店業務指針"
          :provenance "https://www.fsa.go.jp/"
          :commission-rate-cap 0.20
          :required-docs ["顧客ニーズ確認書 (customer needs-assessment record)"
                          "比較見積り一覧 (quote-comparison summary)"
                          "手数料開示書面 (commission disclosure statement)"
                          "本人確認書類"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law Article 21 (Agents and Brokers)"
             :national-spec "NYDFS producer licensing/commission-disclosure regulation"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- producer licensing is regulated per-state; New York is an exemplar, not a national authority."
             :commission-rate-cap 0.20
             :required-docs ["Customer needs-assessment record"
                             "Quote-comparison summary"
                             "Commission disclosure statement"
                             "Producer license verification"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Financial Services and Markets Act 2000 -- ICOBS (Insurance Distribution)"
          :national-spec "FCA Insurance Distribution Directive (IDD) implementation rules"
          :provenance "https://www.fca.org.uk/"
          :commission-rate-cap 0.25
          :required-docs ["Customer needs-assessment record"
                          "Quote-comparison summary"
                          "Commission disclosure statement"
                          "Identity verification"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Versicherungsvermittlungsgesetz (Insurance Mediation Act)"
          :national-spec "IDD-Umsetzungsverordnung (IDD implementation regulation)"
          :provenance "https://www.bafin.de/"
          :commission-rate-cap 0.20
          :required-docs ["Bedarfsanalyse (customer needs-assessment record)"
                          "Angebotsvergleich (quote-comparison summary)"
                          "Provisionsoffenlegung (commission disclosure statement)"
                          "Identitätsnachweis"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to bind a placement
  on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6622 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `intermediation.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-docs-satisfied?
  "Does `submitted` (a set/coll of doc keywords or strings) satisfy every
  required doc listed for `iso3`? Missing spec-basis -> never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-docs]} (spec-basis iso3)]
    (let [need (count required-docs)
          have (count (filter (set submitted) required-docs))]
      (= need have))))

(defn doc-checklist [iso3]
  (:required-docs (spec-basis iso3) []))

(defn commission-rate-cap [iso3]
  (:commission-rate-cap (spec-basis iso3)))

# shinkansen — 仕様

**shinkansen は、view・state・agent 向け tool 面のすべてを CID で address する、
Kotoba stack 用 content-addressed web framework である。** 名前が可変な場所に
アプリの要素は 1 つも置かない — link は CID、state 遷移は CID、agent が UI を
取るのも browser と同じ address である。

Status: R0（2026-09-13、ADR-2609131808 accepted）。

---

## 1. 設計原理

### 1.1 identity は CID、Location は設定

ADR-2609092600 の app 4 面を framework の primitive としてそのまま使う:

    identity  ipfs://{cid}                       不変。app が記録する唯一のアドレス
    naming    DNSLink / IPNS                     可変な版
    bytes     https://{cid}.ipfs.kotobase.net    Location 1
    entry     https://{name}.itonami.app/        Location 2

- **HTML の link は CID そのもの**: `href="https://{cid}.ipfs.kotobase.net/"` が
  1 等公民。`{cid}.ipfs.yataverse.com`（mirror zone、ADR-2609131630）も同値。
- **`:document` は自己完結の 1 ファイル**。CDN から asset を取りに行く document は
  CID を名乗れない — `shinkansen.publish/manifest` は外部 asset 参照を
  **理由付きで拒否**する（どの URL が違反かを `:external` で返す）。
  CID gateway への link（`{cid}.ipfs.*` / `{cid}.ipns.*` / path 形
  `ipfs.kotobase.net/ipfs/{cid}`）は content 自身なので違反ではない。

### 1.2 Unison 的 content addressing

state も content である。`shinkansen.state/chain-entry` は re-frame の
`db + event → db` の**結果の db 値**を EDN テキストにして hash し、CID を取る:

    {:db       <新しい db 値>
     :db-cid   "bafkrei…"          その content address
     :prev     <前の entry の CID>  head では nil
     :event    <dispatch された event、verbatim>
     :height   3}

- **同じ db 値に至る別の経路は同じ CID に着地する**（`same-db-value-same-cid`
  test が pin）。これが「diff を patch する」代わりに「state 全体を address する」
  Unison 的モデル。
- **chain は検証可能**: `walk-chain` が各 entry の `:db-cid` を自分の `:text` から
  再計算して照合する。1 つでも壊れていれば `:failed-at` に height を出して fail closed。
- **hash 関数は注入**（`cid-fn`）。workspace の content-address lib（本番）と
  guest の `:hash/sha256` capability（kbb）の両方で同じ chain が作れる。

### 1.3 js / event は host の権限（framework は触らない）

- guest（`.kotoba`、amu compile）は **inert document を返すだけ**。shitsuke の
  `reframe_core.kotoba`（db+event→db、effects を host へ）と同じ境界。
- event の実装は既に host 側にある: dom-gpu の `:dom/add-event-listener` op
  （multi-listener、hit-test + bubbling、browser_events.cljk の click/key-down）。
- kbb wire-ids に DOM capability を**足さない**。js 操作は host の権限であり、
  guest が ambient authority を持つことは ADR-2608650000 が恒久に禁止している。

### 1.4 agent は tool call で来る

`shinkansen.mcp` が MCP（stdio JSON-RPC）面を出す。LLM agent は screen ではなく
tool で lake を読み、UI document を取り、dispatch を投げる:

| tool | 先 | 用途 |
|---|---|---|
| `lake_list` | GET /api/v1/lake/blocks | cursor page で最近の block 一覧（CID・size・gateway URL） |
| `lake_head` | GET /api/v1/lake/head | 現在の IPNI advertisement tip |
| `lake_fetch` | GET /ipfs/{cid} | 1 block の取得（meta または text） |
| `lake_dispatch` | （app 側） | state chain への event dispatch、新しい db 値と CID を返す |

失敗は throw しない: `{:ok false :error <理由>}` を返す。CIDv0（`Qm…`）は
`lake_fetch` が理由を名指して拒否する。

---

## 2. モジュール

    src/shinkansen/state.cljc    db 値 → CID chain（純粋、hash fn 注入、persistence は caller）
    src/shinkansen/publish.cljc  publish manifest（自己完結検査 + scripts/publish-document.cljk の argv）
    src/shinkansen/mcp.cljc      MCP tool 宣言 + dispatch（純粋、handler 注入）
    src/shinkansen/locale.cljc   locale negotiation 契約（cookie ベース、path 非依存、純粋）
    src/shinkansen/viewport.cljc multi-screen-size 契約（viewport meta + xs band、静的 audit）
    src/shinkansen/audit.cljc    UI/UX document 契約 = 決定論的 fitness function（13 軸、理由付き finding）
    src/shinkansen/coscientist.cljc Generate→Reflect→Rank(Elo)→Evolve→Meta の kaizen loop（judge = audit）
    test/                        59 tests / 160 assertions, 0 fail 0 error（nbb via kbb）

### 2.1 publish の 2 面契約

実体の publish は root の `scripts/publish-document.cljk`（archive = kotobase.net
`PUT /ipfs/{cid}` → B2、origin = R2 `ipld/{cid}`）。shinkansen はその**前段**:
自己完結検査 → manifest → argv。**1 面だけに書いた publish は web 面で 502 になる**
（archive のみの書き込みは bytes 面で 200・web 面で 502 — 過去の誤診の実測）。

### 2.2 MCP の protocol 契約

- `initialize` → `{protocolVersion, capabilities: {tools}, serverInfo}`（server 名 `shinkansen`）
- `tools/list` → 4 tool の declaration（`base-url` は注入、test は任意 host に向けられる）
- `tools/call` → handler dispatch。handler 無し tool は
  `{:ok false :error "tool not implemented"}` — **宣言済みで実装無しは見える**（silent skip しない）
- 未知 method → JSON-RPC error `-32601`
- **declaration と dispatch 表の同型 test** が「tools/list に載っているのに呼べない」
  tool の発生を落ちるようにしている

### 2.3 locale negotiation は HOST/edge の権限（document は path を fork しない）

オーナー決定（app-kotoba.cloud の実測に基づく）: URL path による locale 選択
（`/ja/about`）は dead link と IA 断片化を生む（22 locale × path 複製）。shinkansen
の framework default は **1 route = 1 language-agnostic document、locale は
cookie（Accept-Language フォールバック付き）で negotiate、言語切替は client 側で
cookie を書く**。

- `negotiate` — 優先順位: cookie > Accept-Language q 値 > :default。cookie 値は
  :supported に無ければ **fail-closed で無視**（stale/forged cookie は host が
  出してない locale に固定できない）。結果は `:source`（:cookie /
  :accept-language / :default）を記録する。
- `set-cookie-header` — Set-Cookie 属性は**この 1 箇所**で serial 化。各 app が
  属性を再導出するのを禁止（app-kotoba.cloud の kb_locale と同じ形: Path=/
  SameSite=Lax/Secure/Max-Age=1y）。
- `substitute` — build 時生成 document の SSR seam。shitsuke の i18n table
  （sign_in_i18n 型: source 文字列 → request 時に localize）と対になる。未知の
  文字列は source のまま通す（翻訳欠落は空白ではなく source で見える — 内容は
  fail-open、locale **選択**は fail-closed）。
- `document-variants` — content-addressing 側: 1 route → N locale document、
  それぞれ別 CID（publish/manifest と同じ自己完結検査を通る）。**identity は
  per-locale-CID のまま、name/route は locale 非依存、negotiate は edge/host が
  cookie で行う** — `/ja/` 型の path fork は作らない。

locale negotiation は document ではなく HOST/edge に属する。shinkansen はその
純粋契約だけを提供する（render はしない、cookie を読む IO もしない）。

### 2.4 UI/UX document 契約は fitness function である（`shinkansen.audit` + `shinkansen.coscientist`）

オーナー指示（2026-09-15、`kotoba.cloud/account` の実測「uiux 品質があまり高くない」）:
framework の改善は co-scientist の approach で進める —— **測れない品質は劇場**
（keiei-arbor ADR-2606141500 / isekai.ux ADR-0007 / 90-docs/design-quality と同系）。

`shinkansen.audit/score-document` は emitted HTML 1 枚を 13 軸で採点し、各 miss を
**理由付きで名指し**する。軸はすべて live page で実測した失敗から起こした
（2026-09-15 の `/account`: 20.5 / 100、12 finding）:

| 軸 | 実測した失敗 |
|---|---|
| `:assets-resolve` | `/js/session.js` が本番で 404 —— document は届くが hydrate せず、全 cell が永遠に「読み込んでいます…」 |
| `:unique-ids` | `account-refresh`×4 / `account-org-create`×2 —— 2 個目以降は listener の付かない死んだ button |
| `:idle-pending` | idle document に 12 個の loading cell —— readiness ではなく捏造された progress |
| `:nav-one-home` | sidebar の `/account`×3、`data-current`×3 |
| `:nav-before-content` | phone band で 13 link（1,148px）の nav が本文の前に来る |
| `:skip-link` / `:plain-labels` / `:idle-disabled` / `:repeated-actions` / `:note-density` / `:locale-path-links` / `:fixed-anchor` | 同 page で各 1 件以上 |

- **測れない軸は pass にしない**: asset set を渡さないと `:assets-resolve` は
  `:unmeasured` に載り平均から除外される（0 でも 1 でもない）。0 枚の audit は
  `:empty? true` で overall 0。
- 可視性は構造で判定する（`hidden` 属性、閉じた `<details>` の summary 以外）。
  idle 系の軸は browser が既定で隠すものを数えない。
- `shinkansen.coscientist/kaizen-cycle` は finding ごとに hypothesis を 1 本
  （owner = :consumer / :deploy / :framework、effort）、Elo round-robin（K=32、
  headroom 主導・決定論的）で rank、low-risk consumer + 全 deploy 行を batch に
  evolve、iteration 文書を出す。**unmeasured が 1 つでもあれば converged にしない。**
  `delta` が before/after の軸別の測定 —— roadmap は予測、delta が証明。
- consumer 側の使い方: emitted document 群 + 公開 asset set を渡して audit し、
  床（`--min`）を gate にする。deploy 直前に asset set 込みで 1 度走らせること
  （`:assets-resolve` が deploy 起因の outage を止める唯一の場所）。

---

## 3. 検証

```bash
kbb -M:test        # 59 tests / 160 assertions, 0 failures, 0 errors
```

⚠ `test_runner` の `-main` に**列挙されていない** test ns は require されても走らない。
217c338 の `viewport-test` はこの形で 1 度も走っておらず、走らせると
`no-xs-band-fails` が落ちた（`:no-xs-band` の比較が逆: 最小 band が 480px **未満**
のときに flag していた。2026-09-15 修正）。ns を足すときは require と doseq の両方。

- `same-db-value-same-cid` — 異なる event 経路で同じ db 値 → 同一 CID
- `corrupted-entry-fails-closed` — 改竄 entry は理由を名指して拒否
- `mid-chain-corruption-names-its-height` — 壊れた位置（height）を報告
- `document-with-external-asset-is-refused-by-name` — 外部 asset は URL 付きで拒否
- `gateway-references-are-not-external` — CID gateway link は違反でない
- `lake-fetch-refuses-non-cid-fail-closed` — CIDv0 は handler に届く前に拒否
- `declared-tools-match-dispatch-table` — tools/list と dispatch の表が同型
- `account-like-document-names-every-failure` — /account の実測失敗 12 種を 1 文書に再現し、各軸の finding 文言を pin
- `unmeasured-assets-are-not-a-pass` — asset set 無しは `:unmeasured`（score nil、平均から除外）
- `converged-only-when-clean-and-fully-measured` — unmeasured / 0 枚では converged にならない

---

## 4. 依存と非依存

**使う**: shitsuke（view/state の .kotoba guest 群、変更しない）、
content-address / io-ipld / io-multiformats（hash・CID）、
`scripts/publish-document.cljk`（2 面publish の実体）、
kotoba-server `word mcp`（MCP stdio の形）。

**使わない（恒久）**: Svelte/React（ADR-2608260900）、guest からの js 操作、
DOM capability wire の新設、mutable naming に載せる document（IPNS/DNSLink は
naming 面であって document の identity ではない）。

## 5. 他 framework との対応

shinkansen は一つの層であり、単独では web にならない。上下の層と何を所有し・
何を所有しないかの対応表 (上位 = 表現、下位 = 基盤):

| 層 | 所有する | 所有しない |
|---|---|---|
| **jp-go-dds** (orgs/kotoba-lang/jp-go-dds) | design tokens / base parts (page/->page, dds.css) | app shell pattern, document publish, CID |
| **cloud-kotoba-dds** (orgs/kotoba-lang/cloud-kotoba-dds) | app shell / patterns: shell, account-entry, navigation (docs/design.md レイヤー表) | CID / MCP (発行面は持たない) |
| **app-kotoba-cloud site.cljk kc-*** (orgs/kotoba-lang/app-kotoba-cloud) | その shell の現行 SSR 実装。`site-layout` が chrome (header/nav/footer) の唯一の定義 | CID publish (静的 Worker Static Assets のみ) |
| **shinkansen** (この repo) | content-addressed document publish (CID = identity) + state chain + MCP lake tools — 上記 shell が作った document を**配送する** 計画面 | routing / IA / visual shell (kc-* と site-layout が正。shinkansen は再定義しない) |

方向は一方向: jp-go-dds ← cloud-kotoba-dds ← app-kotoba-cloud → shinkansen。
app-kotoba-cloud の site-layout が chrome の SSOT である限り、shinkansen 側で
visual shell を複製しないこと (ADR-2609092600 :document の自己完結原則は
「asset を document に同梱する」ことであり「shell を再実装する」ことではない)。

## 6. 次の一段（未実施、実測の順）
1. **guest bridge**: shitsuke の `reframe_core.kotoba` を shinkansen の state
   chain に繋ぐ `.kotoba` bridge module（db 値の EDN text を guest から出す）
2. **yataverse lake index への着地**: worktree
   `net-kotobase-ipfs-yataverse-index` に着手済みの top page / `/api/v1/lake/*`
   を shinkansen publish 経由に置き換え
3. **MCP stdio server**: `mcp.cljc` の dispatch を stdin/stdout loop に載せる
4. **west pin**: `west-entry-add`（entry 名は repo 名と一致させる規約。既存
   cloud-itonami/shinkansen と entry 名が衝突するため、repo 名と west entry 名の
   整合を west-entry-add 実行時に要確認 — 未着手の既知残）

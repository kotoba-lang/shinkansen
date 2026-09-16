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
    src/shinkansen/audit.cljc    UI/UX document 契約 = 決定論的 fitness function（19 軸、理由付き finding）
    src/shinkansen/coscientist.cljc Generate→Reflect→Rank(Elo)→Evolve→Meta の kaizen loop（judge = audit）
    src/shinkansen/interaction.cljc browser 側の契約（data-action / data-params、run stream、hydrate、theme、locale）+ 1 本の runtime
    src/shinkansen/theme.cljc    light / dark / system の契約（storage、属性、head-script、theme/set）
    test/                        76 tests / 211 assertions, 0 fail 0 error（nbb via kbb）

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

### 2.3b browser 側の interaction は framework の契約（`shinkansen.interaction`、2026-09-16）

実測: cloud-itonami-app の `interaction.js` は 14,352 行で、共通 component の `script`
（cloud-kotoba-dds shell / chat / bot）とも重複して、hook で要素を探す・要素ごとに listener を
付ける・list を container に描く・`data: <json>` の run stream を読む・mount を hydrate する、を
それぞれ手で書いている。framework が持つのは**契約**と**runtime 1 本**、host が持つのは
「action が何をするか」「stream の event が何を意味するか」「mount に何を描くか」。

1. **action**: control は `data-action="<event-id>"`（+ 任意の `data-params` JSON）を名乗る。
   runtime は click / submit の delegated listener を 1 本だけ持ち、host が `shinkansen.on(id, fn)`
   で登録した handler を `(params, element, event)` で呼ぶ。id は `shinkansen.actions` が宣言する
   event id と同じ語彙（`:cart/add` ↔ `"cart/add"`）。hiccup 側は `interaction/action-attrs`。
2. **run stream**: `shinkansen.streamRun(url, {onEvent, onClose, onError}, init)` は Hermes 形
   （`data: <json>`、`event` 名付き frame、comment 行は keepalive）の SSE を読む。JVM-free Hermes
   gateway と Bot loop が話す wire なので、chat client はここで 1 度だけ読む。`{abort()}` を返す。
3. **hydrate**: `shinkansen.hydrate(name, fn)` は `[data-hydrate="<name>"]` の未 hydrate な要素に
   `fn` を当てて印を付ける。mount は server が描き、browser は埋めるだけ。

js / event は host の権限（§1.3）のまま —— framework は DOM capability を足さない。runtime は
文字列で、host が自分の file として配るか 1 度 inline する。

### 2.3c theme と locale は framework の選択（`shinkansen.theme` / `shinkansen.locale` の browser 側、2026-09-16）

オーナー指示「言語切り替え, dark, light, system theme switcher も統合」。同日の実測: theme の
選択は jp-go-dds.theme-toggle（2 状態 λ、key `kotoba-theme`、`<html data-theme>`）、
cloud-itonami-app の `data-appearance` toggle（独自 key、独自 `:root:has()` scope、server が
外した appearance を JS がまだ回す）、OS 追従だけの page、の 3 通りが並存していた。契約は 1 つ:

- **theme**: mode は `light | dark | system`。storage `kotoba-theme` に `light|dark`、**無い = system**。
  `<html data-theme="light|dark">`、system は属性を**外す**（CSS が決して当てない値を入れると黙って
  light になる）。CSS は `jp-go-dds.dark/dark-css`（`:root:root[data-theme]` が media block に勝つ）。
  `theme/head-script` を `<head>` に置いて paint 前に適用、runtime の `shinkansen.theme`
  （`get / effective / set / onChange`）が属性・storage・switcher を同期し、OS の変更にも
  `shinkansen:theme` event で追従する。action は `theme/set {mode}`。
- **locale**: action は `locale/set {locale}`。runtime の `shinkansen.locale.set` が §2.3 の
  negotiation cookie（`defaults` の属性から**導出**、Secure は https のときだけ）を書き、control の
  `href` へ移動するか reload する —— document は host/edge が再 negotiate し、client で描き直さない。
- 両 action は **framework-actions** として全 declaration に merge され、`:actions-declared` 軸は
  宣言無しでも declared と数える。host は `shinkansen.on` で上書きできる。

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

- **2026-09-15 第 2 周（オーナー実測「/docs/ 下層で layout が崩れる・ページが見つからない」）で足した 3 軸**:
  `:links-resolve`（same-origin の `<a href>` は公開 document か宣言 route に解決する —
  sidebar が `/docs/` を link しながら `/docs/index.html` を emit していなかった = framework
  自身が書いた 404）、`:csp-allows-assets`（host の CSP が document 自身の stylesheet /
  script を許す — `style-src 'unsafe-inline'` の下で外部 CSS に移した瞬間、全頁が
  無スタイルで届きながら bytes の audit は 100 だった）、`:pre-overflow`（`<pre>` を持つ
  document に `overflow-x:auto` — phone で code が行の途中で切れる）。ctx に `:documents` /
  `:routes` / `:csp`（文字列、または CSP を配らないことの明示 `:none`）を渡す。
- **2026-09-15 第 4 周（オーナー実測「右上のヘッダーがスクロールする」「左下のメニューが
  単に広がる」）で足した 1 軸**: `:chrome-layers` — document が**宣言した** chrome はその層を保つ。
  `data-chrome=top` を付けた要素（console の top bar）には `position:sticky|fixed` + block anchor
  の rule が、`data-chrome=float` を付けた要素（popover menu）には `position:absolute|fixed` +
  `z-index` の rule が、その marker を名指しで当たっていること。marker が契約であって class 名
  ではない（cloud-kotoba-dds.shell が両方の marker を emit する）。menu は hidden のまま測る
  —— 開く前に層の rule が無ければならない。宣言の無い document は測るものが無く 1.0。
- **2026-09-15 第 5 周（オーナー実測「code area が見ずらい」）で足した 1 軸**: `:code-language` —
  可視の `<pre>` は自分の言語を名乗る（`<pre data-lang>` か、子 `<code class="language-x">`。
  cloud-kotoba-dds.code/block が両方を emit する）。名乗らない block は 1 色の壁で、token 化も
  読み上げもできない。`<pre hidden>`（script が埋める JSON dump）は data であって block ではなく
  数えない。score は名乗った block の割合、finding は件数と直し方。`:pre-overflow` はそのまま
  「scroll か wrap できること」を測り続ける。
- **2026-09-16（オーナー指示「interaction などを共通化」）で足した 1 軸**: `:actions-declared` —
  document の control が名乗る `data-action` id は、`shinkansen.actions` の宣言（ctx `:actions`）に
  在る event である。browser と agent の dispatch 語彙は 1 つなので、宣言に無い id を名乗る
  control は surface が `:undeclared-event` で拒否する死んだ control。control が 1 つも無い
  document は **`:not-applicable`**（score でも unmeasured でもなく平均から外れる —— 1.0 を
  配ると control の無い静的頁が全部持ち上がる。実測: account-like fixture 39.6 → 40.8）。
  宣言無しで control が在る document は `:unmeasured`。
- **2026-09-16（オーナー実測「docs.kotoba.cloud/graph/ の uiux が崩れている」）で足した 1 軸**:
  `:classes-styled` — document が要素に付けた class 名は、その document が運ぶ CSS（inline
  `<style>` か、ctx `:stylesheets` で渡された same-origin stylesheet）の selector に当たっている。
  上の marker 軸は document が**宣言した**もの（`data-chrome` / `data-action` / `data-lang`）しか
  見ないので、shipped されない stylesheet 向けに書かれた markup は何も宣言せず何も落とさない ——
  実測: `/docs/graph/` は marketing header / footer の markup を emit しながら token bridge しか
  inline せず、54 class 中 27 に rule が無く、header は生リンクの羅列・skip link は出っぱなしのまま
  audit は 98.3。selector だけを見る（`url(x.png)` の `.png` や宣言の `.5rem` は rule ではない）。
  許容は **5 分の 1、ただし最低 1 つ**（rule の無い hook class は健全な頁にも在る。実測 3 / 58、
  10 / 122）。超過分は線形に落ち、5 分の 3 で 0。class を 1 つも持たない document は
  `:not-applicable`、stylesheet が渡されていなければ `:unmeasured`。finding は名前を sorted で
  最大 12 個 + 件数、直し方は「shared shell（site-layout / docs-page）を通すか、書いた CSS を
  inline する」。
- **2026-09-16（オーナー指示「Radix 相当の behavior 層」）で足した 1 軸**: `:behaviors-delivered` —
  document が `data-behavior=…`（jp-go-dds.behavior: dialog / menu / tabs / disclosure /
  radiogroup / toast / combobox）を宣言したら、その marker を select する script が**運ばれて**
  いて（inline か、ctx `:scripts` で渡された same-origin script）、かつ subtree が契約の markup
  （menu なら `[data-menu-opener][aria-expanded][aria-controls]` と
  `[data-menu-popup][data-chrome=float]`、tabs なら tablist / tab[aria-selected] / tabpanel、
  combobox なら `[role=combobox]` の aria 3 点と `[role=listbox]`、dialog は `<dialog>` +
  aria-labelledby、toast は role=status + aria-live、radiogroup は role=radiogroup +
  radio[aria-checked]、disclosure は aria-expanded + aria-controls）を持つこと。runtime 無しの
  markup（deploy が script を忘れた /account の形）も、markup が足りない runtime も、class は
  1 つも変わらないまま死んだ control になる —— どちらも finding で名指す。marker の無い
  document は `:not-applicable`、参照した script が渡されていなければ `:unmeasured`。
  score は「守られた宣言 / 宣言」。契約表は `shinkansen.audit/behavior-contract`（data）。
- **1 本の emit tree を複数 host が分け合うとき**、document ごとの `:ctx` を shared ctx に
  merge する（docs host の page は自分の surface の `:documents` に対して link を解決する）。
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
kbb -M:test        # 76 tests / 211 assertions, 0 failures, 0 errors
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

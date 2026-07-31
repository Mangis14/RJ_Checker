# RJ Seat Checker

Zistí, kto sedí okolo teba v RegioJete, od ktorej stanice sa susedné miesto uvoľní,
a navrhne pokojnejšie miesto vo vlaku.

## Čo to vie

```bash
python prototype/rjseat.py --date 2026-08-01 --departure 21:45 --coach 6 --seat 32
```

```
Vlak RJ 1021   Praha - hl.n. 21:45 -> Košice - žst. 06:12   (2026-08-01)
Vozen Vuz Bk (42) - Standard (2. tr.)
Tvoje miesto 32   oddiel 31,32,33,34,35,36 (cislovanie miest)

Kto je okolo teba (usek -> Košice - žst.):
  miesto 31  vedla      obsadene po celej trase
  miesto 33  vedla      obsadene po celej trase
  miesto 35  oproti     obsadene po celej trase
  miesto 34  v oddiele  obsadene po celej trase
  miesto 36  v oddiele  obsadene po celej trase
```

Odporúčanie pokojnejšieho miesta (`--recommend C0` pre Standard, `C1` Relax, `C2` Business):

```
Najpokojnejsie volne miesta v triede Standard (2. tr.):
  vozen 2  miesto 1    dvojica 1,2                cele volne celu cestu
  vozen 3  miesto 21   kupe 21,22,23,24,25,26     volne 3/5, cele prazdne od Poprad - Tatry (04:34)
  vozen 6  miesto 11   kupe 11,12,13,14,15,16     volne 2/5, cele prazdne od Kysak (05:56)
```

Sledovanie zmien — nahlási, keď sa susedné miesto uvoľní alebo ho niekto zaberie:

```bash
python prototype/rjseat.py --date 2026-08-01 --departure 21:45 --coach 6 --seat 32 --watch 300
```

Ďalšie prepínače: `--from` / `--to` (id stanice, default Praha hl.n. → Košice žst.),
`--json out.json`. Bez `--departure` vypíše dostupné spoje. Žiadne závislosti,
stačí Python 3.8+.

## Čo to o spolucestujúcich vie a čo nie

RegioJet zverejňuje **iba obsadenosť miest** — či je miesto voľné pre daný úsek.
Žiadne údaje o cestujúcich neexistujú a tento nástroj ich nehľadá. „Info
o spolucestujúcich“ tu znamená: ktoré miesta okolo teba sú obsadené a kde ten
človek nastupuje alebo vystupuje. Na otázku „budem mať vedľa seba niekoho?“ to
stačí.

## Ako to funguje

Obsadenosť sa číta z toho istého backendu, z ktorého číta web RegioJetu
(`POST /routes/freeSeats`). Odpoveď obsahuje **všetky vozne naraz**, takže jeden
prechod zastávkami stačí na analýzu vlastného miesta aj na odporúčanie.

Kľúčová vlastnosť dát: obsadenosť platí pre **úsek**. Miesto voľné pre úsek
`<stanica> → cieľ` je voľné po celý zvyšok cesty — kratší úsek ho už obsadiť
nemôže. Preto sa „pokoj“ počíta zo stavu na začiatku a prechod zastávkami slúži
na opačnú otázku: kde sa *obsadené* miesto uvoľní.

### Topológia miest

API neposiela žiadne súradnice sedadiel — `Seat` má len `index`, `seatClass`,
`seatConstraint` a `seatNotes`. „Vedľa“ a „oproti“ sa preto odvodzuje z SVG
layoutu vozňa ([tools/seatgeom.py](tools/seatgeom.py)):

- **oddiely (kupé)** primárne z **číslovania miest** — RegioJet čísluje kupé po
  desiatkach (1–6, 11–16, 21–26 …) a diery v číslach padnú presne tam, kde kupé
  končí. Je to spoľahlivejšie než geometria.
- **vedľa / oproti** zo súradníc: dlhšia os = dĺžka vozňa, zhlukovanie do radov
  a stĺpcov, ulička = výrazne najväčšia medzera medzi stĺpcami.
- v kupé sa „vedľa“ počíta v rámci oddielu — kupé uličku vnútri nemá, chodbička
  vedie vedľa neho.
- miesta otočené k sebe sú **ďalej** od seba než dve lavice za sebou
  (face-to-face potrebuje priestor na nohy pre dvoch, back-to-back len dve
  operadlá). Táto orientácia sa nehádá, vyplýva z toho.

`seatConstraint` z API dopĺňa to, čo z obrázka odvodiť nemožno: **miesto pri
stolíku**, **tiché kupé**, **detské kupé**, vozeň len pre ženy. Odporúčanie za
tiché kupé pripočítava, za detské odpočítava.

### Kde sú hranice

Layout vozňa **Relax `Bm3xx (54)`** sa nedá prečítať spoľahlivo — jeho SVG je
o verziu starší než číslovanie z API (má miesto 26, API má 35) a stĺpce sa
nezhlukujú čisto, takže sa nenájde ulička. V takom prípade nástroj **prizná
neistotu** a ukáže viac kandidátov namiesto jedného nesprávneho miesta.
Je to zapísané ako očakávanie v testoch, nie zametené pod koberec.

Ďalšia vec: pri **pretrasovaných spojoch** (výluka) má základný cestovný poriadok
správnu množinu zastávok, ale nesprávne časy. Reálny čas sa dá overiť len pre
úseky, ktoré RegioJet predáva; ostatné sú označené `~`. Zastávky, z ktorých sa
nastúpiť nedá, sa **nezahadzujú** — vlak tam zastavuje a obsadenosť pre ne platí,
takže práve tam je vidno, kde niekto vystupuje.

## Android appka

Modul [app/](app) je tenká Compose vrstva nad `core`. Postup v appke:

1. **Odkiaľ / kam / dátum** → nájde priame vlaky (default Praha hl.n. → Košice žst.)
2. **Vyber spoj** → načíta súpravu jedným volaním
3. **Vyber vozeň a svoje miesto** → mriežka miest, zelené voľné, sivé obsadené
4. **Výsledok** → kto je okolo teba, od ktorej stanice sa uvoľní, pokojnejšie miesta,
   a prepínač **Sledovať toto miesto**

Načítanie je zámerne dvojfázové. Výber miesta potrebuje jedno volanie (odpoveď
obsahuje všetky vozne), kým otázka „od ktorej stanice sa miesto uvoľní" vyžaduje
prechod všetkými zastávkami — asi 30 volaní. Preto sa zoznam miest zobrazí hneď
a plná analýza dobehne na pozadí, namiesto toho, aby si čakal na začiatku.

Sledovanie beží cez WorkManager každých 15 minút (to je minimum, ktoré Android
pre periodickú prácu povoľuje) a notifikuje **len pri skutočnej zmene** voči
poslednému uloženému stavu.

### Build APK

Android SDK **nie je nainštalovaný na hostovi** — celé build prostredie žije
v Docker obraze, takže sa dá zmazať jedným `docker image rm rjseat-android` a na
stroji po ňom nič nezostane. Licencie Android SDK sa odklikávajú pri stavbe
obrazu, nie na tvojom systéme.

```powershell
.\docker\build.ps1                    # debug APK -> app/build/outputs/apk/debug/
.\docker\build.ps1 -Task :core:test   # len testy
.\docker\build.ps1 -Rebuild           # znovu postaviť obraz
```

Gradle cache je v pojmenovanom volume `rjseat-gradle-cache`, takže druhý build
už nesťahuje závislosti odznova (prvý ~5 min, ďalšie ~1 min).

APK stavia aj CI ([.github/workflows/build.yml](.github/workflows/build.yml)):
testy `core` gatujú build, potom sa postaví debug APK a nahrá ako artefakt —
Actions → príslušný beh → `rjseat-debug-apk`.

## Kotlin core (základ Android appky)

Modul [core/](core) je **čistý Kotlin/JVM bez Android závislostí** — celá logika
(parsovanie, topológia miest, odporúčanie, sledovanie zmien) sa preto dá testovať
lokálne bez Android SDK. Android modul nad ním bude len tenká vrstva.

```bash
./gradlew :core:test
```

23 testov, bežia z fixtures bez siete. Stavia sa na tom, čo sa dá obhájiť
z fyzickej podoby vozňa — napríklad že kupéčkový vozeň má 6-miestne kupé s dvoma
miestami vedľa a jedným oproti, a že veľkopriestorový 2+2 vozeň má presne jedného
suseda vedľa.

Dva testy stoja za zmienku, lebo zachytávajú chyby, ktoré tu naozaj boli:

- `odporucanie nikdy nesluby pokoj do stanice pred vychodzou` — pôvodná verzia
  hlásila „pokoj do Olomouc" pri mieste voľnom už z Prahy, čo je logicky nemožné.
- `prve kolo nehlasi nic` — bez toho by appka pri každom spustení vypálila
  notifikáciu o zmene, ktorá sa nestala.

Build adresár je zámerne mimo projektu (`~/.rjseat-build`, prebije sa premennou
`RJSEAT_BUILD_DIR`) — projekt leží v OneDrive a jeho synchronizácia drží súbory
v `build/` otvorené, takže ich Gradle nedokáže zmazať.

## Testy

```bash
./gradlew :core:test        # Kotlin core, 23 testov
python tools/validate_geom.py   # referenčná geometria, 10 layoutov vozňov
```

Fixtures ([fixtures/](fixtures)) sú reálne odpovede API + SVG layouty, takže
testy bežia bez siete. Obnoviť sa dajú cez `python tools/capture_fixtures.py`.

## Poznámka k API

RegioJet má oficiálny **Affiliate API** (verzia 1.1.0, spec na SwaggerHub,
kontakt `developers@studentagency.cz`), ktorý vyžaduje HTTP Basic Auth. Tento
prototyp číta ten istý verejný backend ako web — bez auth, len na čítanie, tie
isté verejne zobrazené dáta. Pre osobný nástroj to funguje; pre čokoľvek
dlhodobé alebo zverejnené je správnou cestou požiadať o affiliate prístup.

Dve veci, v ktorých sa **publikovaný spec rozchádza s nasadeným API**:

- `RouteSeatsRequest` uvádza `tariffs` a `seatClass` ako povinné polia na
  najvyššej úrovni, ale nasadený endpoint ich takto odmieta
  (`request.body.json.property.unrecognized`). Reálne očakáva vnorený
  `seatPreference: {seatClass, tariffs}`.
- `Content-Type` **aj** `Accept` musia byť `application/1.1.0+json`. S bežným
  `application/json` vráti endpoint HTTP 400 „Unexpected error“.

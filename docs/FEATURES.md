# Funkcie — referenčná dokumentácia

Čo appka robí, ako sa rozhoduje a kde sú hranice. Pre inštaláciu a build pozri
[README](../README.md).

Pri každej funkcii je uvedené aj **kde v kóde žije**, aby sa dala zmena nájsť bez
hľadania.

---

## Obsah

1. [Základ: čo appka vôbec vie o obsadenosti](#1-základ-čo-appka-vôbec-vie-o-obsadenosti)
2. [Vyhľadanie spoja](#2-vyhľadanie-spoja)
3. [Pohodlie spoja na prvý pohľad](#3-pohodlie-spoja-na-prvý-pohľad)
4. [Triedy a typy sedadiel](#4-triedy-a-typy-sedadiel)
5. [Výber vlastného miesta](#5-výber-vlastného-miesta)
6. [Analýza susedstva](#6-analýza-susedstva)
7. [Odporúčanie pokojnejšieho miesta](#7-odporúčanie-pokojnejšieho-miesta)
8. [Sledovanie miesta na pozadí](#8-sledovanie-miesta-na-pozadí)
9. [Sledovanie celej triedy](#9-sledovanie-celej-triedy)
10. [Notifikácie](#10-notifikácie)
11. [Hospodárenie s dátami a batériou](#11-hospodárenie-s-dátami-a-batériou)
12. [Známe hranice](#12-známe-hranice)

---

## 1. Základ: čo appka vôbec vie o obsadenosti

Celá appka stojí na jednej vlastnosti dát, ktorú treba pochopiť, inak sa všetko
ostatné číta zle.

**Obsadenosť platí pre ÚSEK, nie pre okamih.** Odpoveď na otázku „ktoré miesta sú
voľné" sa pýta pre konkrétny úsek `A → B`. Miesto voľné pre úsek
`východzia → cieľ` je voľné **po celú cestu** — kratší úsek ho už obsadiť nemôže.

Z toho plynú dva dôsledky, ktoré sa opakujú v celej appke:

- „Pokoj" sa počíta zo stavu na **začiatku** cesty. Ak je miesto voľné z východzej
  stanice, nikto si k tebe cestou neprisadne.
- Prechod zastávkami odpovedá na **opačnú** otázku: kde sa *obsadené* miesto
  uvoľní, teda kde ten človek vystupuje.

Miesta appka nikdy nerezervuje ani nekupuje — iba číta to, čo web zobrazuje
verejne.

**O spolucestujúcich neexistujú žiadne údaje.** RegioJet zverejňuje výhradne
obsadenosť miest. „Info o spolucestujúcich" v tejto appke znamená: ktoré miesta
okolo teba sú obsadené a kde ten človek nastupuje alebo vystupuje. Žiadne mená,
žiadne osobné údaje — a appka ich ani nehľadá.

Kód: [`Journey`](../core/src/main/kotlin/io/github/mangis14/rjchecker/core/Journey.kt)

---

## 2. Vyhľadanie spoja

Zadáš odkiaľ, kam a dátum; appka nájde **priame vlakové spoje** v ten deň.

- Stanice sa načítajú z API a filtrujú sa na vlakové v ČR a SR.
- Default je Praha hl.n. → Košice žst., dá sa prepnúť aj otočiť smer.
- Dátum sa vyberá z vodorovného zoznamu 28 dní dopredu.

**Prestupové spoje appka nepodporuje** — berie len `transfersCount == 0`.
Topológia miest naprieč viacerými úsekmi by bola samostatná práca a na trase,
pre ktorú appka vznikla, prestup netreba.

Kód: `RjClient.directTrains`, `RjClient.stations`

---

## 3. Pohodlie spoja na prvý pohľad

V zozname spojov je pri každom vlaku zhrnutie, **koľko pokoja ponúka** — ešte pred
výberom vozňa:

```
21:45 – 06:12                              283 voľných
[2× prázdne kupé]  [12× prázdna dvojica]
najpokojnejšie: vozeň 3, miesto 21 – celý oddiel voľný
```

Kľúčové je, že to zoznam **nespomalí**. Keďže obsadenosť platí pre úsek (viď
kapitolu 1), na zhrnutie stačí **jedno volanie na spoj**. Zoznam sa zobrazí hneď
a údaje dobiehajú po jednom na pozadí.

Kód: `Journey.comfortSummary`, `SeatViewModel.loadComfort`

---

## 4. Triedy a typy sedadiel

Ku každej triede sa zobrazí počet voľných miest a ich rozpad na typy:

```
[Relax]      12 voľných  (4× samostatné, 8× dvojica)
[Low cost]   Vypredané                          [Strážiť]
```

### Triedy

Kľúče z API sú technické (`C0`, `C1`, `C2`), takže sa prekladajú a zobrazujú ako
farebné tagy. Farby sú volené tak, aby dávali zmysel samy o sebe — čím vyšší
komfort, tým teplejšia a sýtejšia farba:

| trieda | farba | prečo |
|---|---|---|
| Low cost | neutrálna sivá | nič navyše |
| Standard | pokojná modrá | základ |
| Relax | zelená | oddych |
| Business | zlatá | premium |
| Lôžko / ležadlo | tlmená nočná modrofialová | noc, pokoj |

Neznámy kľúč dostane neutrálny štýl a pôvodný názov — radšej surový kľúč než
nesprávny tag.

**Vypredané triedy sú v zozname tiež**, s označením „Vypredané". Práve tie má
zmysel strážiť; triedu, kde je voľno, si človek jednoducho kúpi.

Kód: [`ClassStyle.kt`](../app/src/main/kotlin/io/github/mangis14/rjchecker/ClassStyle.kt),
`Journey.availabilityByClass`

### Typy sedadiel

| typ | čo znamená |
|---|---|
| samostatné | nikto vedľa (napr. strana „1" vo vozni Relax 2+1) |
| dvojica | dve miesta vedľa seba |
| štvorica so stolíkom | štyri miesta otočené k sebe |
| kupé | oddiel pre 5 a viac |

Typ sa odvodzuje **z topológie vozňa, nie z API**. Príznak „miesto pri stolíku"
posiela RegioJet len pri vozni Astra (24 miest) a aj tam len ako upozornenie na
chýbajúcu obrazovku — ako všeobecný marker stolíka sa použiť nedá.

Kód: `CoachLayout.seatKind`

---

## 5. Výber vlastného miesta

Vyberieš vozeň a v mriežke svoje miesto. Zelené = voľné, sivé = obsadené.

**Obsadené miesto sa dá vybrať tiež.** Ak už máš lístok, tvoje vlastné miesto je
z pohľadu API obsadené — sedíš na ňom ty. Bez toho by sa hlavný scenár „sleduj
susedov môjho miesta" nedal vôbec spustiť. Po klepnutí sa appka spýta, či je to
tvoje zakúpené miesto, a potom pokračuje na analýzu.

Načítanie je **dvojfázové**:

| fáza | volania | na čo stačí |
|---|---|---|
| rýchla | 1 | zoznam vozňov a miest |
| plná | ~30 | od ktorej stanice sa ktoré miesto uvoľní |

Preto sa mriežka miest zobrazí okamžite a plná analýza dobehne na pozadí.

Kód: `SeatViewModel.selectTrain`, `SeatViewModel.selectSeat`,
`JourneyLoader.load(firstStopOnly)`

---

## 6. Analýza susedstva

Pre vybrané miesto appka povie, kto je okolo a od ktorej stanice sa ktoré miesto
uvoľní:

```
Vozeň 6 · miesto 32 · oddiel 31, 32, 33, 34, 35, 36
miesto 31 (vedľa)   uvoľní sa v Spišská Nová Ves (05:00)
miesto 35 (oproti)  obsadené po celej trase
```

### Ako sa určí, kto je „vedľa" a kto „oproti"

API neposiela o miestach **žiadne súradnice** — `Seat` má len `index`,
`seatClass`, `seatConstraint` a `seatNotes`. Topológia sa preto odvodzuje z SVG
layoutu vozňa:

1. **Oddiely (kupé) primárne z číslovania miest.** RegioJet čísluje kupé po
   desiatkach (1–6, 11–16, 21–26 …) a diery v číslach padnú presne tam, kde kupé
   končí. Je to spoľahlivejšie než geometria.
2. **Vedľa / oproti zo súradníc.** Dlhšia os = dĺžka vozňa; miesta sa zhluknú do
   radov a stĺpcov, ulička je výrazne najväčšia medzera medzi stĺpcami.
3. V kupé sa „vedľa" počíta v rámci oddielu — kupé uličku vnútri nemá, chodbička
   vedie vedľa neho.
4. Miesta otočené k sebe sú **ďalej** od seba než dve lavice za sebou
   (face-to-face potrebuje priestor na nohy pre dvoch, back-to-back len dve
   operadlá). Orientácia sa teda nehádá, vyplýva z toho.

**Keď sa layout prečítať nedá, appka to prizná** a ukáže viac kandidátov namiesto
jedného nesprávneho miesta (`Confidence.UNCERTAIN`).

Kód: [`SeatGeometry.kt`](../core/src/main/kotlin/io/github/mangis14/rjchecker/core/SeatGeometry.kt),
`Journey.analyseSeat`

---

## 7. Odporúčanie pokojnejšieho miesta

Zoznam miest, kde budeš mať najviac pokoja. Z odporúčania sa dá **rovno skočiť**
na dané miesto.

Skóre stavia len na tom, čo je overiteľné:

| zložka | váha |
|---|---|
| podiel voľných miest v oddiele | +4,0 |
| celý oddiel voľný po celú cestu | +1,5 a +0,4 za každé miesto v ňom |
| oddiel sa aspoň vyprázdni po ceste | +1,0 |
| tiché kupé | +2,5 |
| detské kupé | −3,0 |

Tiché a detské kupé sa rozpoznajú z poľa `seatConstraint`, ktoré posiela API.

Dve pravidlá proti nezmyselnému výpisu:

- **Každý oddiel je v zozname len raz.** Osem takmer rovnakých miest z jedného
  kupé vyzerá ako výber, ale žiadny nie je.
- **Najviac dva návrhy na vozeň**, aby bol výber pestrý.

Kód: `Journey.recommend`

---

## 8. Sledovanie miesta na pozadí

Sleduje konkrétne miesto a hlási zmeny na susedných miestach.

- Sledovať sa dá **viac spojov naraz** — cesta tam aj späť, alebo dva kandidátske
  vlaky. Každý má vlastný snapshot, takže sa zmeny hlásia nezávisle.
- Sledovania na **rovnakom vlaku a úseku** sa načítajú raz a vyhodnotia sa nad
  tými istými dátami — sledovať miesto aj triedu v jednom vlaku stojí jedno
  stiahnutie.
- Na prvej obrazovke je zoznam sledovaných s možnosťou zrušiť.
- Sledovanie sa **10 h po odchode samo zruší**. Bez toho by ťahalo dáta aj týždne
  po skončenej ceste.

Kód: [`WatchWorker.kt`](../app/src/main/kotlin/io/github/mangis14/rjchecker/WatchWorker.kt),
[`WatchSchedule.kt`](../core/src/main/kotlin/io/github/mangis14/rjchecker/core/WatchSchedule.kt)

---

## 9. Sledovanie celej triedy

Namiesto konkrétneho miesta sa dá strážiť **celá trieda v spoji** — „daj vedieť,
keď sa uvoľní hocijaký Relax". Zapína sa tlačidlom pri triede v zozname spojov,
netreba na to vyberať vozeň ani miesto.

Sleduje sa **celý vlak**, nie jeden vozeň, takže snapshot drží dvojice
vozeň-miesto.

**Pohodlie sa označuje, nefiltruje.** Či je voľné aj miesto vedľa, sa v texte
uvedie, ale upozornenie sa kvôli tomu nezadrží. Pri vypredanej triede treba
vedieť o každom uvoľnenom mieste — filter by práve to prvé zamlčal, lebo vedľa
neho ešte niekto sedí.

Kód: `Journey.freeSeatsInClass`, `WatchWorker.checkClass`

---

## 10. Notifikácie

Chodia **len pri skutočnej zmene** voči poslednému uloženému stavu. Prvé kolo
nehlási nič — inak by appka pri každom spustení vypálila notifikáciu o zmene,
ktorá sa nestala.

Poradie dôležitosti:

1. **Celý uvoľnený oddiel** — najsilnejší signál, dá sa presunúť a cestovať sám
2. **Zmena na susedných miestach** — to, na čo sa človek pýta
3. **Ostatné miesta vo vozni**

Každá notifikácia uvádza **čísla miest**, nie len počet — „uvoľnilo sa miesto vo
vozni 7" nepovie, kam si sadnúť. Dlhý zoznam sa skráti na `12, 31, 45 +3 ďalších`.

Klepnutie otvorí appku priamo na analýze daného miesta, a to aj keď už beží.
Každý sledovaný spoj má vlastné ID notifikácie, takže si navzájom neprepisujú.

Kód: `SeatWatcher`, `WatchWorker.notify`

---

## 11. Hospodárenie s dátami a batériou

Kontrola beží cez WorkManager, prebudenie každých 15 minút (systémové minimum;
v Doze to Android naťahuje). Appka medzitým **nebeží** — systém ju prebudí,
kontrola trvá pár sekúnd, proces sa ukončí. Žiadne GPS, žiadne trvalé spojenie.

Nie každé prebudenie znamená sieť:

| situácia | čo sa stane |
|---|---|
| viac ako deň pred odchodom | kontrola len každé 4. prebudenie (~1×/h) |
| menej ako deň pred odchodom a počas cesty | každé prebudenie (4×/h) |
| 10 h po odchode | sledovanie sa zruší |

Jedna kontrola stiahne **~135 kB** (`freeSeats` 130 kB + `routeDetail` 5 kB).

Dôležité: **server neposiela gzip** — rovnaká veľkosť s aj bez `Accept-Encoding`,
takže sa to nedá zmenšiť. Hlavička `X-Occupied` z dokumentácie na tomto nasadení
veľkosť nemení.

SVG layouty vozňov (~88 kB) sa **ukladajú na disk**. Layout typu vozňa sa nemení,
takže z opakovaných kontrol vypadne úplne.

| | za hodinu |
|---|---|
| plánovanie dopredu | ~135 kB |
| deň pred / počas cesty | ~540 kB |
| po ceste | 0 |

Batéria je zanedbateľná — pár sekúnd sieťovej aktivity na prebudenie.

Kód: `WatchSchedule`, [`FileLayoutStore.kt`](../app/src/main/kotlin/io/github/mangis14/rjchecker/FileLayoutStore.kt)

---

## 12. Známe hranice

Veci, ktoré appka **nevie** alebo vie nespoľahlivo. Sú tu zámerne, aby sa
nevydávali za funkčné.

### Vozeň Relax `Bm3xx (54)`

Jeho SVG sa nedá prečítať spoľahlivo: je o verziu staršie než číslovanie z API
(má miesto 26, API má 35) a stĺpce sa nezhluknú čisto, takže sa nenájde ulička.
Vozeň je v skutočnosti **2+1** (dvojice a samostatné miesta), nie 2+2.

Appka v takom prípade **prizná neistotu** a ukáže viac kandidátov namiesto jedného
nesprávneho miesta. Typ sedadla sa neuvedie.

### Prestupové spoje

Podporované sú len priame vlaky.

### Prihlásenie RegioJet účtom

Nie je a nebude — ich login je chránený podpisom tela požiadavky (HMAC SHA3-512
s kľúčom z ich bundle) a reCAPTCHA. Obísť to znamená prelomiť opatrenia proti
cudzím klientom. Sankcionovaná cesta je požiadať o affiliate prístup na
`developers@studentagency.cz`.

### Pretrasované spoje

Pri výluke má základný cestovný poriadok správnu množinu zastávok, ale nesprávne
časy. Reálny čas sa dá overiť len pre úseky, ktoré RegioJet predáva; ostatné sú
označené `~`. Zastávky, z ktorých sa nedá nastúpiť, sa **nezahadzujú** — vlak tam
zastavuje a obsadenosť pre ne platí, takže práve tam je vidno, kde niekto
vystupuje.

### Tiché hodiny

Nie sú. Notifikácie chodia aj v noci.

### Varovanie Play Protect

Pri inštalácii sa ukáže „Play Protect hasn't seen an app from this developer
before". Je to normálne pre každú sideloadovanú aplikáciu a odstránilo by to len
vydanie cez Google Play.


import json
import os
import re
import site
import tempfile

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

_dll_handles = []

for root in site.getsitepackages():
    for library in ("cublas", "cudnn"):
        folder = Path(root) / "nvidia" / library / "bin"

        if folder.is_dir():
            _dll_handles.append(
                os.add_dll_directory(str(folder))
            )

            os.environ["PATH"] = (
                    str(folder)
                    + os.pathsep
                    + os.environ.get("PATH", "")
            )

            print("NVIDIA DLL:", folder, flush=True)

from faster_whisper import WhisperModel
from huggingface_hub import snapshot_download

HOST = "127.0.0.1"
PORT = 47630

REPO = "coriollon/whisper-large-v3-turbo-russian-codeswitch"


# ============================================================
# TECHNICAL TERMS LIBRARY
# ============================================================
#
# Каждое правило:
#     (регулярное выражение, правильное написание)
#
# (?<!\w) и (?!\w) предотвращают замену внутри других слов.
#
# Сначала идут длинные и специфичные термины.
# Затем короткие названия и сокращения.
#
# Не добавляй слишком общие замены вроде:
#     "пост" -> "POST"
#     "класс" -> "class"
#     "куда" -> "CUDA"
#
# Они способны портить обычную речь.
# ============================================================

TECHNICAL_TERMS = [

    # ========================================================
    # 1. JAVA COLLECTIONS AND DATA STRUCTURES
    # ========================================================

    (
        r"(?<!\w)(?:concurant|concurent|concurrant)"
        r"[\s_-]*hash[\s_-]*map(?!\w)",
        "ConcurrentHashMap"
    ),
    (
        r"(?<!\w)(?:юзер[\s_-]*репозитори(?:й|я|ю|и|ем)|"
        r"user[\s_-]*repository)(?!\w)",
        "UserRepository"
    ),
    (
        r"(?<!\w)(?:mock[\s_-]*ito|мок[\s_-]*ито|"
        r"мокито|mockito)(?!\w)",
        "Mockito"
    ),
    (
        r"(?<!\w)(?:линкит|линкед)"
        r"[\s_-]*(?:hash[\s_-]*map|хэш[\s_-]*мап)(?!\w)",
        "LinkedHashMap"
    ),
    (
        r"(?<!\w)(?:юнит[\s_-]*тесты?|"
        r"юни[\s_-]*тесты?)(?!\w)",
        "unit tests"
    ),
    (
        r"(?<!\w)(?:concurrent[\s_-]*hash[\s_-]*map|"
        r"конкурент[\s_-]*х[еэа]ш[\s_-]*м[аэе]п|"
        r"конкуренция[\s_-]*шмат)(?!\w)",
        "ConcurrentHashMap"
    ),
    (
        r"(?<!\w)(?:linked[\s_-]*hash[\s_-]*map|"
        r"линкед[\s_-]*хэш[\s_-]*мап)(?!\w)",
        "LinkedHashMap"
    ),
    (
        r"(?<!\w)(?:hash[\s_-]*map|"
        r"х[еэа]ш[\s_-]*м[аэе]п)(?!\w)",
        "HashMap"
    ),
    (
        r"(?<!\w)(?:tree[\s_-]*map|"
        r"три[\s_-]*м[аэ]п)(?!\w)",
        "TreeMap"
    ),
    (
        r"(?<!\w)(?:copy[\s_-]*on[\s_-]*write[\s_-]*array[\s_-]*list|"
        r"копи[\s_-]*он[\s_-]*райт[\s_-]*эррей[\s_-]*лист)(?!\w)",
        "CopyOnWriteArrayList"
    ),
    (
        r"(?<!\w)(?:array[\s_-]*list|"
        r"эрр[еэ]й[\s_-]*лист|аррей[\s_-]*лист)(?!\w)",
        "ArrayList"
    ),
    (
        r"(?<!\w)(?:linked[\s_-]*list|"
        r"линкед[\s_-]*лист)(?!\w)",
        "LinkedList"
    ),
    (
        r"(?<!\w)(?:linked[\s_-]*hash[\s_-]*set|"
        r"линкед[\s_-]*хэш[\s_-]*сет)(?!\w)",
        "LinkedHashSet"
    ),
    (
        r"(?<!\w)(?:hash[\s_-]*set|"
        r"хэш[\s_-]*сет)(?!\w)",
        "HashSet"
    ),
    (
        r"(?<!\w)(?:tree[\s_-]*set|"
        r"три[\s_-]*сет)(?!\w)",
        "TreeSet"
    ),
    (
        r"(?<!\w)(?:priority[\s_-]*queue|"
        r"приорити[\s_-]*кью)(?!\w)",
        "PriorityQueue"
    ),
    (
        r"(?<!\w)(?:blocking[\s_-]*queue|"
        r"блокинг[\s_-]*кью)(?!\w)",
        "BlockingQueue"
    ),
    (
        r"(?<!\w)(?:concurrent[\s_-]*linked[\s_-]*queue)(?!\w)",
        "ConcurrentLinkedQueue"
    ),
    (
        r"(?<!\w)(?:string[\s_-]*builder|"
        r"стринг[\s_-]*билдер)(?!\w)",
        "StringBuilder"
    ),
    (
        r"(?<!\w)(?:string[\s_-]*buffer|"
        r"стринг[\s_-]*бафер|стринг[\s_-]*буфер)(?!\w)",
        "StringBuffer"
    ),
    (
        r"(?<!\w)(?:big[\s_-]*decimal|"
        r"биг[\s_-]*десимал)(?!\w)",
        "BigDecimal"
    ),
    (
        r"(?<!\w)(?:local[\s_-]*date[\s_-]*time|"
        r"локал[\s_-]*дейт[\s_-]*тайм)(?!\w)",
        "LocalDateTime"
    ),
    (
        r"(?<!\w)(?:local[\s_-]*date|"
        r"локал[\s_-]*дейт)(?!\w)",
        "LocalDate"
    ),
    (
        r"(?<!\w)(?:local[\s_-]*time|"
        r"локал[\s_-]*тайм)(?!\w)",
        "LocalTime"
    ),
    (
        r"(?<!\w)(?:zoned[\s_-]*date[\s_-]*time)(?!\w)",
        "ZonedDateTime"
    ),
    (
        r"(?<!\w)(?:completable[\s_-]*future|"
        r"комплит[аэ]бл[\s_-]*фьючер)(?!\w)",
        "CompletableFuture"
    ),
    (
        r"(?<!\w)(?:optional|опш[еэ]нал)(?!\w)",
        "Optional"
    ),
    (
        r"(?<!\w)(?:integer|инт[еэ]джер|интеджер)(?!\w)",
        "Integer"
    ),
    (
        r"(?<!\w)(?:uuid|ю[\s_-]*ю[\s_-]*ай[\s_-]*ди)(?!\w)",
        "UUID"
    ),


    # ========================================================
    # 2. JAVA CLASSES AND METHODS
    # ========================================================

    (
        r"(?<!\w)(?:user[\s_-]*manager|"
        r"юзер[\s_-]*менеджер)(?!\w)",
        "UserManager"
    ),
    (
        r"(?<!\w)(?:user[\s_-]*controller|"
        r"юзер[\s_-]*контроллер)(?!\w)",
        "UserController"
    ),
    (
        r"(?<!\w)(?:user[\s_-]*service|"
        r"юзер[\s_-]*сервис)(?!\w)",
        "UserService"
    ),
    (
        r"(?<!\w)(?:user[\s_-]*repository|"
        r"юзер[\s_-]*репозиторий)(?!\w)",
        "UserRepository"
    ),
    (
        r"(?<!\w)(?:user[\s_-]*dto|"
        r"юзер[\s_-]*ди[\s_-]*ти[\s_-]*оу)(?!\w)",
        "UserDTO"
    ),
    (
        r"(?<!\w)(?:get[\s_-]*user[\s_-]*by[\s_-]*id|"
        r"гет[\s_-]*юзер[\s_-]*бай[\s_-]*айди)(?!\w)",
        "getUserById"
    ),
    (
        r"(?<!\w)(?:get[\s_-]*user[\s_-]*name|"
        r"г[еэ]т[\s_-]*юзер[\s_-]*н[еэа]й?м)(?!\w)",
        "getUserName"
    ),
    (
        r"(?<!\w)(?:set[\s_-]*user[\s_-]*name|"
        r"сет[\s_-]*юзер[\s_-]*н[еэа]й?м)(?!\w)",
        "setUserName"
    ),
    (
        r"(?<!\w)(?:add[\s_-]*user|"
        r"[аэ]дд?[\s_-]*юзер)(?!\w)",
        "addUser"
    ),
    (
        r"(?<!\w)(?:remove[\s_-]*user|"
        r"р[еи]мув[\s_-]*юзер|ремоув[\s_-]*юзер)(?!\w)",
        "removeUser"
    ),
    (
        r"(?<!\w)(?:find[\s_-]*by[\s_-]*id|"
        r"файнд[\s_-]*бай[\s_-]*айди)(?!\w)",
        "findById"
    ),
    (
        r"(?<!\w)(?:find[\s_-]*all|"
        r"файнд[\s_-]*ол)(?!\w)",
        "findAll"
    ),
    (
        r"(?<!\w)(?:delete[\s_-]*by[\s_-]*id|"
        r"делит[\s_-]*бай[\s_-]*айди)(?!\w)",
        "deleteById"
    ),
    (
        r"(?<!\w)(?:get[\s_-]*name|"
        r"гет[\s_-]*нейм)(?!\w)",
        "getName"
    ),
    (
        r"(?<!\w)(?:set[\s_-]*name|"
        r"сет[\s_-]*нейм)(?!\w)",
        "setName"
    ),
    (
        r"(?<!\w)(?:to[\s_-]*string|"
        r"ту[\s_-]*стринг)(?!\w)",
        "toString"
    ),
    (
        r"(?<!\w)(?:hash[\s_-]*code|"
        r"хэш[\s_-]*код)(?!\w)",
        "hashCode"
    ),
    (
        r"(?<!\w)(?:compare[\s_-]*to|"
        r"компэр[\s_-]*ту)(?!\w)",
        "compareTo"
    ),
    (
        r"(?<!\w)(?:system[\s._-]*out[\s._-]*println|"
        r"систем[\s_-]*аут[\s_-]*принт[\s_-]*эл[\s_-]*эн)(?!\w)",
        "System.out.println"
    ),
    (
        r"(?<!\w)(?:@[\s_-]*override|"
        r"оверрайд[\s_-]*аннотация)(?!\w)",
        "@Override"
    ),
    (
        r"(?<!\w)(?:@[\s_-]*test|"
        r"аннотация[\s_-]*тест)(?!\w)",
        "@Test"
    ),
    (
        r"(?<!\w)(?:объект[\s_-]*user)(?!\w)",
        "объект User"
    ),


    # ========================================================
    # 3. JAVA FRAMEWORKS AND TESTING
    # ========================================================

    (
        r"(?<!\w)(?:spring[\s_-]*boot|"
        r"спринг[\s_-]*бут)(?!\w)",
        "Spring Boot"
    ),
    (
        r"(?<!\w)(?:spring[\s_-]*security|"
        r"спринг[\s_-]*секьюрити)(?!\w)",
        "Spring Security"
    ),
    (
        r"(?<!\w)(?:spring[\s_-]*data[\s_-]*jpa|"
        r"спринг[\s_-]*дата[\s_-]*джей[\s_-]*пи[\s_-]*эй)(?!\w)",
        "Spring Data JPA"
    ),
    (
        r"(?<!\w)(?:spring[\s_-]*mvc|"
        r"спринг[\s_-]*эм[\s_-]*ви[\s_-]*си)(?!\w)",
        "Spring MVC"
    ),
    (
        r"(?<!\w)(?:hibernate|хайберн[еэ]йт|хибернейт)(?!\w)",
        "Hibernate"
    ),
    (
        r"(?<!\w)(?:jakarta[\s_-]*ee|"
        r"джакарта[\s_-]*и[\s_-]*и)(?!\w)",
        "Jakarta EE"
    ),
    (
        r"(?<!\w)(?:junit[\s_-]*p?[\s_-]*(?:5|five|пять)|"
        r"джунит[\s_-]*(?:5|пять)|"
        r"джей[\s_-]*юнит[\s_-]*(?:5|пять))(?!\w)",
        "JUnit 5"
    ),
    (
        r"(?<!\w)(?:junit|джунит|джей[\s_-]*юнит)(?!\w)",
        "JUnit"
    ),
    (
        r"(?<!\w)(?:unit[\s_-]*tests?|"
        r"юнит[\s_-]*тесты|юни[\s_-]*тесты)(?!\w)",
        "unit tests"
    ),
    (
        r"(?<!\w)units(?=\s+с\s+использованием)",
        "unit tests"
    ),
    (
        r"(?<!\w)(?:integration[\s_-]*tests?|"
        r"интеграционные[\s_-]*тесты)(?!\w)",
        "integration tests"
    ),
    (
        r"(?<!\w)(?:end[\s_-]*to[\s_-]*end[\s_-]*tests?|"
        r"е[\s_-]*ту[\s_-]*е[\s_-]*тесты)(?!\w)",
        "end-to-end tests"
    ),
    (
        r"(?<!\w)(?:mockito|мокито)(?!\w)",
        "Mockito"
    ),
    (
        r"(?<!\w)(?:assert[\s_-]*equals|"
        r"ассерт[\s_-]*иквалс)(?!\w)",
        "assertEquals"
    ),
    (
        r"(?<!\w)(?:assert[\s_-]*true|"
        r"ассерт[\s_-]*тру)(?!\w)",
        "assertTrue"
    ),
    (
        r"(?<!\w)(?:assert[\s_-]*false|"
        r"ассерт[\s_-]*фолс)(?!\w)",
        "assertFalse"
    ),
    (
        r"(?<!\w)(?:assert[\s_-]*throws|"
        r"ассерт[\s_-]*троус)(?!\w)",
        "assertThrows"
    ),
    (
        r"(?<!\w)(?:assertj|ассерт[\s_-]*джей)(?!\w)",
        "AssertJ"
    ),
    (
        r"(?<!\w)(?:test[\s_-]*containers?|"
        r"тест[\s_-]*контейнерс)(?!\w)",
        "Testcontainers"
    ),
    (
        r"(?<!\w)(?:maven|мейвен|м[эе]йв[еэ]н)(?!\w)",
        "Maven"
    ),
    (
        r"(?<!\w)(?:gradle|гр[еэ]йдл|градл)(?!\w)",
        "Gradle"
    ),
    (
        r"(?<!\w)(?:lombok|ломбок)(?!\w)",
        "Lombok"
    ),
    (
        r"(?<!\w)(?:jackson|джексон)(?!\w)",
        "Jackson"
    ),
    (
        r"(?<!\w)(?:gson|джи[\s_-]*сон)(?!\w)",
        "Gson"
    ),
    (
        r"(?<!\w)(?:map[\s_-]*struct|"
        r"мэп[\s_-]*стракт)(?!\w)",
        "MapStruct"
    ),
    (
        r"(?<!\w)(?:swagger|сваггер)(?!\w)",
        "Swagger"
    ),
    (
        r"(?<!\w)(?:open[\s_-]*api|"
        r"оупен[\s_-]*эй[\s_-]*пи[\s_-]*ай)(?!\w)",
        "OpenAPI"
    ),
    (
        r"(?<!\w)(?:java[\s_-]*fx|джава[\s_-]*эф[\s_-]*икс)(?!\w)",
        "JavaFX"
    ),
    (
        r"(?<!\w)(?:jvm|джей[\s_-]*ви[\s_-]*эм)(?!\w)",
        "JVM"
    ),
    (
        r"(?<!\w)(?:jdk|джей[\s_-]*ди[\s_-]*кей)(?!\w)",
        "JDK"
    ),
    (
        r"(?<!\w)(?:jre|джей[\s_-]*ар[\s_-]*и)(?!\w)",
        "JRE"
    ),
    (
        r"(?<!\w)(?:jdbc|джей[\s_-]*ди[\s_-]*би[\s_-]*си)(?!\w)",
        "JDBC"
    ),
    (
        r"(?<!\w)(?:jpa|джей[\s_-]*пи[\s_-]*эй)(?!\w)",
        "JPA"
    ),


    # ========================================================
    # 4. GIT, GITHUB AND VERSION CONTROL
    # ========================================================

    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:cherry[\s_-]*pick|черри[\s_-]*пик)(?!\w)",
        "git cherry-pick"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:checkout|чекаут)(?!\w)",
        "git checkout"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:commit|comit|comet|коммит|комит|комет|"
        r"к[ао]мит|кормит)(?!\w)",
        "git commit"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:push|пуш|п[ао]ш)(?!\w)",
        "git push"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:pull|пулл?)(?!\w)",
        "git pull"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:clone|клон)(?!\w)",
        "git clone"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:fetch|фетч)(?!\w)",
        "git fetch"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:status|статус)(?!\w)",
        "git status"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:branch|бр[еа]нч)(?!\w)",
        "git branch"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:switch|свитч)(?!\w)",
        "git switch"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:merge|м[еэ]рдж)(?!\w)",
        "git merge"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:rebase|риб[еэ]йс)(?!\w)",
        "git rebase"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:stash|стэш)(?!\w)",
        "git stash"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:reset|ресет)(?!\w)",
        "git reset"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:revert|рев[еэ]рт)(?!\w)",
        "git revert"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:diff|диф)(?!\w)",
        "git diff"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:log|лог)(?!\w)",
        "git log"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:add|эдд?)(?!\w)",
        "git add"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:init|инит)(?!\w)",
        "git init"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:remote|ремоут)(?!\w)",
        "git remote"
    ),
    (
        r"(?<!\w)(?:git|гит|гет)[\s._-]*"
        r"(?:tag|тег)(?!\w)",
        "git tag"
    ),
    (
        r"(?<!\w)(?:github[\s_-]*actions|"
        r"гит[\s_-]*хаб[\s_-]*экш[еэ]нс)(?!\w)",
        "GitHub Actions"
    ),
    (
        r"(?<!\w)(?:github|гит[\s_-]*хаб|гитхаб)(?!\w)",
        "GitHub"
    ),
    (
        r"(?<!\w)(?:gitlab|гит[\s_-]*лаб)(?!\w)",
        "GitLab"
    ),
    (
        r"(?<!\w)(?:bitbucket|бит[\s_-]*бакет)(?!\w)",
        "Bitbucket"
    ),
    (
        r"(?<!\w)(?:pull[\s_-]*request|"
        r"пулл?[\s_-]*реквест)(?!\w)",
        "pull request"
    ),
    (
        r"(?<!\w)(?:merge[\s_-]*request|"
        r"мердж[\s_-]*реквест)(?!\w)",
        "merge request"
    ),
    (
        r"(?<!\w)(?:code[\s_-]*review|"
        r"код[\s_-]*ревью)(?!\w)",
        "code review"
    ),
    (
        r"(?<!\w)(?:code[\s_-]*base|"
        r"код[\s_-]*бейс)(?!\w)",
        "codebase"
    ),
    (
        r"(?<!\w)(?:\.gitignore|git[\s._-]*ignore|"
        r"гит[\s_-]*игнор)(?!\w)",
        ".gitignore"
    ),
    (
        r"(?<!\w)(?:бр[еа]нч|branch)(?!\w)",
        "branch"
    ),


    # ========================================================
    # 5. DATABASES AND SQL
    # ========================================================

    (
        r"(?<!\w)(?:postgresql|postgre[\s_-]*sql|"
        r"постгр[еэ]с[\s_-]*к[уью]*[еэ]л|"
        r"постгрес[\s_-]*кьюэл|"
        r"под[\s_-]*грэйс[\s_-]*квейль)(?!\w)",
        "PostgreSQL"
    ),
    (
        r"(?<!\w)(?:mysql|май[\s_-]*эс[\s_-]*кью[\s_-]*эль|"
        r"май[\s_-]*скьюэл)(?!\w)",
        "MySQL"
    ),
    (
        r"(?<!\w)(?:sqlite|эс[\s_-]*кью[\s_-]*лайт|"
        r"скьюлайт)(?!\w)",
        "SQLite"
    ),
    (
        r"(?<!\w)(?:microsoft[\s_-]*sql[\s_-]*server|"
        r"эс[\s_-]*кью[\s_-]*эль[\s_-]*сервер)(?!\w)",
        "Microsoft SQL Server"
    ),
    (
        r"(?<!\w)(?:mongodb|монго[\s_-]*ди[\s_-]*би|"
        r"монгодб)(?!\w)",
        "MongoDB"
    ),
    (
        r"(?<!\w)(?:mariadb|мария[\s_-]*ди[\s_-]*би)(?!\w)",
        "MariaDB"
    ),
    (
        r"(?<!\w)(?:redis|р[еэ]дис)(?!\w)",
        "Redis"
    ),
    (
        r"(?<!\w)(?:elasticsearch|"
        r"эластик[\s_-]*с[еэ]рч)(?!\w)",
        "Elasticsearch"
    ),
    (
        r"(?<!\w)(?:pgadmin|пи[\s_-]*джи[\s_-]*админ)(?!\w)",
        "pgAdmin"
    ),
    (
        r"(?<!\w)(?:dbeaver|ди[\s_-]*бивер|дбивер)(?!\w)",
        "DBeaver"
    ),
    (
        r"(?<!\w)(?:liquibase|ликви[\s_-]*бейс)(?!\w)",
        "Liquibase"
    ),
    (
        r"(?<!\w)(?:flyway|флай[\s_-]*вэй)(?!\w)",
        "Flyway"
    ),
    (
        r"(?<!\w)(?:prisma[\s_-]*orm|призма[\s_-]*орм)(?!\w)",
        "Prisma ORM"
    ),
    (
        r"(?<!\w)(?:no[\s_-]*sql|ноу[\s_-]*эс[\s_-]*кью[\s_-]*эль)(?!\w)",
        "NoSQL"
    ),
    (
        r"(?<!\w)(?:sql|эс[\s_-]*кью[\s_-]*эль|скьюэл)(?!\w)",
        "SQL"
    ),
    (
        r"(?<!\w)(?:orm|оу[\s_-]*ар[\s_-]*эм)(?!\w)",
        "ORM"
    ),
    (
        r"(?<!\w)(?:primary[\s_-]*key|"
        r"праймари[\s_-]*кей)(?!\w)",
        "PRIMARY KEY"
    ),
    (
        r"(?<!\w)(?:foreign[\s_-]*key|"
        r"форин[\s_-]*кей)(?!\w)",
        "FOREIGN KEY"
    ),
    (
        r"(?<!\w)(?:not[\s_-]*null|"
        r"нот[\s_-]*налл)(?!\w)",
        "NOT NULL"
    ),
    (
        r"(?<!\w)(?:group[\s_-]*by|"
        r"груп[\s_-]*бай)(?!\w)",
        "GROUP BY"
    ),
    (
        r"(?<!\w)(?:order[\s_-]*by|"
        r"ордер[\s_-]*бай)(?!\w)",
        "ORDER BY"
    ),
    (
        r"(?<!\w)(?:inner[\s_-]*join|"
        r"иннер[\s_-]*джойн)(?!\w)",
        "INNER JOIN"
    ),
    (
        r"(?<!\w)(?:left[\s_-]*outer[\s_-]*join)(?!\w)",
        "LEFT OUTER JOIN"
    ),
    (
        r"(?<!\w)(?:right[\s_-]*outer[\s_-]*join)(?!\w)",
        "RIGHT OUTER JOIN"
    ),
    (
        r"(?<!\w)(?:full[\s_-]*outer[\s_-]*join)(?!\w)",
        "FULL OUTER JOIN"
    ),
    (
        r"(?<!\w)(?:left[\s_-]*join|"
        r"лефт[\s_-]*джойн)(?!\w)",
        "LEFT JOIN"
    ),
    (
        r"(?<!\w)(?:right[\s_-]*join|"
        r"райт[\s_-]*джойн)(?!\w)",
        "RIGHT JOIN"
    ),
    (
        r"(?<!\w)(?:cross[\s_-]*join|"
        r"кросс[\s_-]*джойн)(?!\w)",
        "CROSS JOIN"
    ),
    (
        r"(?<!\w)(?:create[\s_-]*table|"
        r"кри[еэ]йт[\s_-]*тейбл)(?!\w)",
        "CREATE TABLE"
    ),
    (
        r"(?<!\w)(?:alter[\s_-]*table|"
        r"алтер[\s_-]*тейбл)(?!\w)",
        "ALTER TABLE"
    ),
    (
        r"(?<!\w)(?:drop[\s_-]*table|"
        r"дроп[\s_-]*тейбл)(?!\w)",
        "DROP TABLE"
    ),
    (
        r"(?<!\w)(?:select|с[еэ]лект)(?!\w)",
        "SELECT"
    ),
    (
        r"(?<!\w)(?:insert[\s_-]*into|"
        r"инсерт[\s_-]*инту)(?!\w)",
        "INSERT INTO"
    ),
    (
        r"(?<!\w)(?:having|хэвинг)(?!\w)",
        "HAVING"
    ),
    (
        r"(?<!\w)(?:join|джойн)(?!\w)",
        "JOIN"
    ),
    (
        r"(?<!\w)(?:rollback|ролл[\s_-]*бэк)(?!\w)",
        "ROLLBACK"
    ),
    (
        r"(?<!\w)(?:acid[\s_-]*transactions|"
        r"эйсид[\s_-]*транзакции)(?!\w)",
        "ACID transactions"
    ),


    # ========================================================
    # 6. DOCKER, DEVOPS AND INFRASTRUCTURE
    # ========================================================

    (
        r"(?<!\w)(?:docker[\s_-]*compose|"
        r"док[еэ]р[\s_-]*компо[зс]|"
        r"докер[\s_-]*компост)(?!\w)",
        "Docker Compose"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*file|"
        r"докер[\s_-]*файл)(?!\w)",
        "Dockerfile"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*compose[\s_-]*up|"
        r"докер[\s_-]*композ[\s_-]*ап)(?!\w)",
        "docker compose up"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*compose[\s_-]*down|"
        r"докер[\s_-]*композ[\s_-]*даун)(?!\w)",
        "docker compose down"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*build|"
        r"докер[\s_-]*билд)(?!\w)",
        "docker build"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*run|"
        r"докер[\s_-]*ран)(?!\w)",
        "docker run"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*exec|"
        r"докер[\s_-]*экзек)(?!\w)",
        "docker exec"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*logs?|"
        r"докер[\s_-]*логи)(?!\w)",
        "docker logs"
    ),
    (
        r"(?<!\w)(?:docker[\s_-]*ps)(?!\w)",
        "docker ps"
    ),
    (
        r"(?<!\w)(?:docker|док[еэ]р)(?!\w)",
        "Docker"
    ),
    (
        r"(?<!\w)(?:kubernetes|куберн[еэ]тис|кубернетес)(?!\w)",
        "Kubernetes"
    ),
    (
        r"(?<!\w)(?:kubectl|куб[\s_-]*си[\s_-]*ти[\s_-]*эл)(?!\w)",
        "kubectl"
    ),
    (
        r"(?<!\w)(?:minikube|мини[\s_-]*куб)(?!\w)",
        "Minikube"
    ),
    (
        r"(?<!\w)(?:podman|подман)(?!\w)",
        "Podman"
    ),
    (
        r"(?<!\w)(?:nginx|энджин[\s_-]*икс|"
        r"энжин[\s_-]*икс)(?!\w)",
        "Nginx"
    ),
    (
        r"(?<!\w)(?:apache[\s_-]*kafka|"
        r"апачи[\s_-]*кафка)(?!\w)",
        "Apache Kafka"
    ),
    (
        r"(?<!\w)(?:rabbit[\s_-]*mq|"
        r"рэббит[\s_-]*эм[\s_-]*кью)(?!\w)",
        "RabbitMQ"
    ),
    (
        r"(?<!\w)(?:jenkins|дженкинс)(?!\w)",
        "Jenkins"
    ),
    (
        r"(?<!\w)(?:ci[\s/-]*cd|"
        r"си[\s_-]*ай[\s/-]*си[\s_-]*ди)(?!\w)",
        "CI/CD"
    ),
    (
        r"(?<!\w)(?:devops|дев[\s_-]*опс)(?!\w)",
        "DevOps"
    ),
    (
        r"(?<!\w)(?:terraform|терраформ)(?!\w)",
        "Terraform"
    ),
    (
        r"(?<!\w)(?:ansible|ансибл)(?!\w)",
        "Ansible"
    ),


    # ========================================================
    # 7. PYTHON, AI AND DATA SCIENCE
    # ========================================================

    (
        r"(?<!\w)(?:faster[\s_-]*whisper|"
        r"фастер[\s_-]*виспер|фастер[\s_-]*вишпер)(?!\w)",
        "faster-whisper"
    ),
    (
        r"(?<!\w)(?:ctranslate[\s_-]*2|"
        r"си[\s_-]*транслейт[\s_-]*два)(?!\w)",
        "CTranslate2"
    ),
    (
        r"(?<!\w)(?:hugging[\s_-]*face|"
        r"хагинг[\s_-]*фейс)(?!\w)",
        "Hugging Face"
    ),
    (
        r"(?<!\w)(?:pytorch|пай[\s_-]*торч)(?!\w)",
        "PyTorch"
    ),
    (
        r"(?<!\w)(?:tensorflow|тензор[\s_-]*флоу)(?!\w)",
        "TensorFlow"
    ),
    (
        r"(?<!\w)(?:scikit[\s_-]*learn|"
        r"сайкит[\s_-]*л[еэ]рн)(?!\w)",
        "scikit-learn"
    ),
    (
        r"(?<!\w)(?:numpy|нампай|нампи)(?!\w)",
        "NumPy"
    ),
    (
        r"(?<!\w)(?:pandas|пандас)(?!\w)",
        "pandas"
    ),
    (
        r"(?<!\w)(?:matplotlib|матплотлиб)(?!\w)",
        "Matplotlib"
    ),
    (
        r"(?<!\w)(?:scipy|сайпай)(?!\w)",
        "SciPy"
    ),
    (
        r"(?<!\w)(?:fast[\s_-]*api|фаст[\s_-]*апи)(?!\w)",
        "FastAPI"
    ),
    (
        r"(?<!\w)(?:django|джанго)(?!\w)",
        "Django"
    ),
    (
        r"(?<!\w)(?:flask|фласк)(?!\w)",
        "Flask"
    ),
    (
        r"(?<!\w)(?:pip[\s_-]*install|"
        r"пип[\s_-]*инсталл)(?!\w)",
        "pip install"
    ),
    (
        r"(?<!\w)(?:requirements[\s._-]*txt|"
        r"реквайрментс[\s_-]*тхт)(?!\w)",
        "requirements.txt"
    ),
    (
        r"(?<!\w)(?:venv|ви[\s_-]*энв|виртуал[\s_-]*энв)(?!\w)",
        "venv"
    ),
    (
        r"(?<!\w)(?:python|пайтон|питон)(?!\w)",
        "Python"
    ),
    (
        r"(?<!\w)(?:open[\s_-]*ai|"
        r"оупен[\s_-]*эй[\s_-]*ай|опен[\s_-]*ай)(?!\w)",
        "OpenAI"
    ),
    (
        r"(?<!\w)(?:chat[\s_-]*gpt|"
        r"чат[\s_-]*(?:джи[\s_-]*пи[\s_-]*ти|гпт|джпт))(?!\w)",
        "ChatGPT"
    ),
    (
        r"(?<!\w)(?:whisper|виспер|вишпер|уиспер)(?!\w)",
        "Whisper"
    ),
    (
        r"(?<!\w)(?:vosk|воск)(?!\w)",
        "Vosk"
    ),
    (
        r"(?<!\w)(?:qwen[\s_-]*3[\s_-]*asr|"
        r"квен[\s_-]*три[\s_-]*эй[\s_-]*эс[\s_-]*ар)(?!\w)",
        "Qwen3-ASR"
    ),
    (
        r"(?<!\w)(?:tokenizer|токенайзер)(?!\w)",
        "tokenizer"
    ),
    (
        r"(?<!\w)(?:transformers|трансформерс)(?!\w)",
        "Transformers"
    ),
    (
        r"(?<!\w)(?:cuda|кьюда|кюда)(?!\w)",
        "CUDA"
    ),
    (
        r"(?<!\w)(?:cudnn|кью[\s_-]*ди[\s_-]*эн[\s_-]*эн)(?!\w)",
        "cuDNN"
    ),
    (
        r"(?<!\w)(?:cublas|кью[\s_-]*блас)(?!\w)",
        "cuBLAS"
    ),
    (
        r"(?<!\w)(?:rtx[\s_-]*3050|"
        r"эр[\s_-]*ти[\s_-]*икс[\s_-]*3050)(?!\w)",
        "RTX 3050"
    ),
    (
        r"(?<!\w)(?:gpu|джи[\s_-]*пи[\s_-]*ю)(?!\w)",
        "GPU"
    ),
    (
        r"(?<!\w)(?:cpu|си[\s_-]*пи[\s_-]*ю)(?!\w)",
        "CPU"
    ),
    (
        r"(?<!\w)(?:llm|эл[\s_-]*эл[\s_-]*эм)(?!\w)",
        "LLM"
    ),
    (
        r"(?<!\w)(?:asr|эй[\s_-]*эс[\s_-]*ар)(?!\w)",
        "ASR"
    ),
    (
        r"(?<!\w)(?:tts|ти[\s_-]*ти[\s_-]*эс)(?!\w)",
        "TTS"
    ),
    (
        r"(?<!\w)(?:vad|ви[\s_-]*эй[\s_-]*ди)(?!\w)",
        "VAD"
    ),


    # ========================================================
    # 8. WEB DEVELOPMENT
    # ========================================================

    (
        r"(?<!\w)(?:java[\s_-]*script|"
        r"джава[\s_-]*скрипт)(?!\w)",
        "JavaScript"
    ),
    (
        r"(?<!\w)(?:type[\s_-]*script|"
        r"тайп[\s_-]*скрипт)(?!\w)",
        "TypeScript"
    ),
    (
        r"(?<!\w)(?:react[\s_-]*native|"
        r"реакт[\s_-]*нейтив)(?!\w)",
        "React Native"
    ),
    (
        r"(?<!\w)(?:react|реакт)(?!\w)",
        "React"
    ),
    (
        r"(?<!\w)(?:next[\s._-]*js|некст[\s_-]*джей[\s_-]*эс)(?!\w)",
        "Next.js"
    ),
    (
        r"(?<!\w)(?:node[\s._-]*js|ноуд[\s_-]*джей[\s_-]*эс)(?!\w)",
        "Node.js"
    ),
    (
        r"(?<!\w)(?:vue[\s._-]*js|вью[\s_-]*джей[\s_-]*эс)(?!\w)",
        "Vue.js"
    ),
    (
        r"(?<!\w)(?:angular|ангуляр)(?!\w)",
        "Angular"
    ),
    (
        r"(?<!\w)(?:svelte|свелт)(?!\w)",
        "Svelte"
    ),
    (
        r"(?<!\w)(?:express[\s._-]*js|"
        r"экспресс[\s_-]*джей[\s_-]*эс)(?!\w)",
        "Express.js"
    ),
    (
        r"(?<!\w)(?:nest[\s._-]*js|нест[\s_-]*джей[\s_-]*эс)(?!\w)",
        "NestJS"
    ),
    (
        r"(?<!\w)(?:tailwind[\s_-]*css|"
        r"тейлвинд[\s_-]*си[\s_-]*эс[\s_-]*эс)(?!\w)",
        "Tailwind CSS"
    ),
    (
        r"(?<!\w)(?:bootstrap|бутстрап)(?!\w)",
        "Bootstrap"
    ),
    (
        r"(?<!\w)(?:vite|вит)(?!\w)",
        "Vite"
    ),
    (
        r"(?<!\w)(?:axios|аксиос)(?!\w)",
        "Axios"
    ),
    (
        r"(?<!\w)(?:graphql|граф[\s_-]*кью[\s_-]*эл)(?!\w)",
        "GraphQL"
    ),
    (
        r"(?<!\w)(?:rest[\s_-]*api|рест[\s_-]*апи)(?!\w)",
        "REST API"
    ),
    (
        r"(?<!\w)(?:web[\s_-]*socket|веб[\s_-]*сокет)(?!\w)",
        "WebSocket"
    ),
    (
        r"(?<!\w)(?:web[\s_-]*rtc|веб[\s_-]*эр[\s_-]*ти[\s_-]*си)(?!\w)",
        "WebRTC"
    ),
    (
        r"(?<!\w)(?:npm[\s_-]*install|"
        r"эн[\s_-]*пи[\s_-]*эм[\s_-]*инсталл)(?!\w)",
        "npm install"
    ),
    (
        r"(?<!\w)(?:npm[\s_-]*run[\s_-]*dev)(?!\w)",
        "npm run dev"
    ),
    (
        r"(?<!\w)(?:npm|эн[\s_-]*пи[\s_-]*эм)(?!\w)",
        "npm"
    ),
    (
        r"(?<!\w)(?:npx|эн[\s_-]*пи[\s_-]*икс)(?!\w)",
        "npx"
    ),
    (
        r"(?<!\w)(?:pnpm|пи[\s_-]*эн[\s_-]*пи[\s_-]*эм)(?!\w)",
        "pnpm"
    ),
    (
        r"(?<!\w)(?:jsx|джей[\s_-]*эс[\s_-]*икс)(?!\w)",
        "JSX"
    ),
    (
        r"(?<!\w)(?:tsx|ти[\s_-]*эс[\s_-]*икс)(?!\w)",
        "TSX"
    ),
    (
        r"(?<!\w)(?:html|эйч[\s_-]*ти[\s_-]*эм[\s_-]*эль)(?!\w)",
        "HTML"
    ),
    (
        r"(?<!\w)(?:css|си[\s_-]*эс[\s_-]*эс)(?!\w)",
        "CSS"
    ),
    (
        r"(?<!\w)(?:json|джейсон)(?!\w)",
        "JSON"
    ),
    (
        r"(?<!\w)(?:yaml|ямл|йамл)(?!\w)",
        "YAML"
    ),
    (
        r"(?<!\w)(?:xml|икс[\s_-]*эм[\s_-]*эль)(?!\w)",
        "XML"
    ),
    (
        r"(?<!\w)(?:api|эй[\s_-]*пи[\s_-]*ай|апи)(?!\w)",
        "API"
    ),


    # ========================================================
    # 9. RUST AND CARGO
    # ========================================================

    (
        r"(?<!\w)(?:cargo[\s_-]*build|карго[\s_-]*билд)(?!\w)",
        "cargo build"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*run|карго[\s_-]*ран)(?!\w)",
        "cargo run"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*test|карго[\s_-]*тест)(?!\w)",
        "cargo test"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*check|карго[\s_-]*чек)(?!\w)",
        "cargo check"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*fmt|карго[\s_-]*эф[\s_-]*эм[\s_-]*ти)(?!\w)",
        "cargo fmt"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*clippy|карго[\s_-]*клиппи)(?!\w)",
        "cargo clippy"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*new|карго[\s_-]*нью)(?!\w)",
        "cargo new"
    ),
    (
        r"(?<!\w)(?:cargo[\s_-]*init|карго[\s_-]*инит)(?!\w)",
        "cargo init"
    ),
    (
        r"(?<!\w)(?:cargo|карго)(?!\w)",
        "Cargo"
    ),
    (
        r"(?<!\w)(?:rust|раст)(?!\w)",
        "Rust"
    ),
    (
        r"(?<!\w)(?:println!|принт[\s_-]*эл[\s_-]*эн[\s_-]*макрос)(?!\w)",
        "println!"
    ),
    (
        r"(?<!\w)(?:pub[\s_-]*crate|паб[\s_-]*крейт)(?!\w)",
        "pub(crate)"
    ),
    (
        r"(?<!\w)(?:unwrap|анрэп|анврап)(?!\w)",
        "unwrap"
    ),
    (
        r"(?<!\w)(?:derive[\s_-]*debug|дерив[\s_-]*дебаг)(?!\w)",
        "derive(Debug)"
    ),


    # ========================================================
    # 10. IDE, OPERATING SYSTEMS, FILES
    # ========================================================

    (
        r"(?<!\w)(?:intellij[\s_-]*idea|"
        r"интелли[дж]+[еэ]й[\s_-]*идея|"
        r"интеллидженс[\s_-]*идея)(?!\w)",
        "IntelliJ IDEA"
    ),
    (
        r"(?<!\w)(?:visual[\s_-]*studio[\s_-]*code|"
        r"vs[\s_-]*code|"
        r"ви[\s_-]*эс[\s_-]*код)(?!\w)",
        "VS Code"
    ),
    (
        r"(?<!\w)(?:visual[\s_-]*studio|"
        r"вижуал[\s_-]*студио)(?!\w)",
        "Visual Studio"
    ),
    (
        r"(?<!\w)(?:pycharm|пай[\s_-]*чарм)(?!\w)",
        "PyCharm"
    ),
    (
        r"(?<!\w)(?:webstorm|веб[\s_-]*шторм)(?!\w)",
        "WebStorm"
    ),
    (
        r"(?<!\w)(?:android[\s_-]*studio|андроид[\s_-]*студио)(?!\w)",
        "Android Studio"
    ),
    (
        r"(?<!\w)(?:clion|си[\s_-]*лайон)(?!\w)",
        "CLion"
    ),
    (
        r"(?<!\w)(?:powershell|пауэр[\s_-]*шелл|"
        r"пауэршел)(?!\w)",
        "PowerShell"
    ),
    (
        r"(?<!\w)(?:windows[\s_-]*terminal|"
        r"виндовс[\s_-]*терминал)(?!\w)",
        "Windows Terminal"
    ),
    (
        r"(?<!\w)(?:windows[\s_-]*11|"
        r"виндовс[\s_-]*одиннадцать)(?!\w)",
        "Windows 11"
    ),
    (
        r"(?<!\w)(?:wsl|дабл[\s_-]*ю[\s_-]*эс[\s_-]*эл)(?!\w)",
        "WSL"
    ),
    (
        r"(?<!\w)(?:ubuntu|убунту)(?!\w)",
        "Ubuntu"
    ),
    (
        r"(?<!\w)(?:debian|дебиан)(?!\w)",
        "Debian"
    ),
    (
        r"(?<!\w)(?:linux|линукс)(?!\w)",
        "Linux"
    ),
    (
        r"(?<!\w)(?:macos|мак[\s_-]*ос)(?!\w)",
        "macOS"
    ),
    (
        r"(?<!\w)(?:pom[\s._-]*xml|"
        r"пом[\s_-]*икс[\s_-]*эм[\s_-]*эль)(?!\w)",
        "pom.xml"
    ),


    # ========================================================
    # 11. NETWORKS, SIP AND TELEPHONY
    # ========================================================

    (
        r"(?<!\w)(?:snom[\s_-]*320|"
        r"сном[\s_-]*триста[\s_-]*двадцать)(?!\w)",
        "Snom 320"
    ),
    (
        r"(?<!\w)(?:mini[\s_-]*sip[\s_-]*server|"
        r"мини[\s_-]*сип[\s_-]*сервер)(?!\w)",
        "miniSIPServer"
    ),
    (
        r"(?<!\w)(?:micro[\s_-]*sip|майкро[\s_-]*сип|"
        r"микро[\s_-]*сип)(?!\w)",
        "MicroSIP"
    ),
    (
        r"(?<!\w)(?:vb[\s_-]*cable|"
        r"ви[\s_-]*би[\s_-]*кейбл)(?!\w)",
        "VB-CABLE"
    ),
    (
        r"(?<!\w)(?:voice[\s_-]*over[\s_-]*ip|"
        r"войс[\s_-]*овер[\s_-]*айпи|voip)(?!\w)",
        "VoIP"
    ),
    (
        r"(?<!\w)(?:https|эйч[\s_-]*ти[\s_-]*ти[\s_-]*пи[\s_-]*эс)(?!\w)",
        "HTTPS"
    ),
    (
        r"(?<!\w)(?:http|эйч[\s_-]*ти[\s_-]*ти[\s_-]*пи)(?!\w)",
        "HTTP"
    ),
    (
        r"(?<!\w)(?:tcp|ти[\s_-]*си[\s_-]*пи)(?!\w)",
        "TCP"
    ),
    (
        r"(?<!\w)(?:udp|ю[\s_-]*ди[\s_-]*пи)(?!\w)",
        "UDP"
    ),
    (
        r"(?<!\w)(?:dhcp|ди[\s_-]*эйч[\s_-]*си[\s_-]*пи)(?!\w)",
        "DHCP"
    ),
    (
        r"(?<!\w)(?:dns|ди[\s_-]*эн[\s_-]*эс)(?!\w)",
        "DNS"
    ),
    (
        r"(?<!\w)(?:sip|эс[\s_-]*ай[\s_-]*пи|сип)(?!\w)",
        "SIP"
    ),
    (
        r"(?<!\w)(?:rtp|ар[\s_-]*ти[\s_-]*пи)(?!\w)",
        "RTP"
    ),
    (
        r"(?<!\w)(?:rtsp|ар[\s_-]*ти[\s_-]*эс[\s_-]*пи)(?!\w)",
        "RTSP"
    ),
    (
        r"(?<!\w)(?:jwt|джей[\s_-]*дабл[\s_-]*ю[\s_-]*ти)(?!\w)",
        "JWT"
    ),
    (
        r"(?<!\w)(?:oauth[\s_-]*2(?:\.0)?|"
        r"оу[\s_-]*авт[\s_-]*два)(?!\w)",
        "OAuth 2.0"
    ),
    (
        r"(?<!\w)(?:cors|корс)(?!\w)",
        "CORS"
    ),
    (
        r"(?<!\w)(?:localhost|локал[\s_-]*хост)(?!\w)",
        "localhost"
    ),


    # ========================================================
    # 12. GRAPHICS AND OTHER PROGRAMMING TECHNOLOGIES
    # ========================================================

    (
        r"(?<!\w)(?:lwjgl|эл[\s_-]*дабл[\s_-]*ю[\s_-]*джей[\s_-]*эл)(?!\w)",
        "LWJGL"
    ),
    (
        r"(?<!\w)(?:opengl|оупен[\s_-]*джи[\s_-]*эл)(?!\w)",
        "OpenGL"
    ),
    (
        r"(?<!\w)(?:glfw|джи[\s_-]*эл[\s_-]*эф[\s_-]*дабл[\s_-]*ю)(?!\w)",
        "GLFW"
    ),
    (
        r"(?<!\w)(?:glsl|джи[\s_-]*эл[\s_-]*эс[\s_-]*эл)(?!\w)",
        "GLSL"
    ),
    (
        r"(?<!\w)(?:vulkan|вулкан)(?!\w)",
        "Vulkan"
    ),
    (
        r"(?<!\w)(?:directx|директ[\s_-]*икс)(?!\w)",
        "DirectX"
    ),
    (
        r"(?<!\w)(?:unreal[\s_-]*engine|"
        r"анрил[\s_-]*энджин)(?!\w)",
        "Unreal Engine"
    ),
    (
        r"(?<!\w)(?:blender|блендер)(?!\w)",
        "Blender"
    ),
    (
        r"(?<!\w)(?:maple|мейпл)(?!\w)",
        "Maple"
    ),


    # ========================================================
    # 13. GENERAL PROGRAMMING LANGUAGES AND TERMS
    # ========================================================

    (
        r"(?<!\w)(?:c|с|си)\s*\+\s*\+(?!\w)",
        "C++"
    ),
    (
        r"(?<!\w)(?:c|с|си)[\s_-]*(?:#|sharp|шарп)(?!\w)",
        "C#"
    ),
    (
        r"(?<!\w)(?:f|эф)[\s_-]*(?:#|sharp|шарп)(?!\w)",
        "F#"
    ),
    (
        r"(?<!\w)(?:\.net|дот[\s_-]*нет)(?!\w)",
        ".NET"
    ),
    (
        r"(?<!\w)(?:kotlin|котлин)(?!\w)",
        "Kotlin"
    ),
    (
        r"(?<!\w)(?:swift|свифт)(?!\w)",
        "Swift"
    ),
    (
        r"(?<!\w)(?:scala|скала)(?!\w)",
        "Scala"
    ),
    (
        r"(?<!\w)(?:groovy|груви)(?!\w)",
        "Groovy"
    ),
    (
        r"(?<!\w)(?:php|пи[\s_-]*эйч[\s_-]*пи)(?!\w)",
        "PHP"
    ),
    (
        r"(?<!\w)(?:java|джава)(?!\w)",
        "Java"
    ),
]


COMPILED_TERMS = [
    (
        re.compile(pattern, re.IGNORECASE),
        replacement
    )
    for pattern, replacement in TECHNICAL_TERMS
]


def correct_technical_terms(text):
    for pattern, replacement in COMPILED_TERMS:
        text = pattern.sub(replacement, text)

    return text


print("Загружаю модель Whisper RU+EN...", flush=True)

root = snapshot_download(
    repo_id=REPO,
    allow_patterns="ct2_int8_float16/*"
)

model_path = Path(root) / "ct2_int8_float16"

model = WhisperModel(
    str(model_path),
    device="cuda",
    compute_type="int8_float16"
)

lock = Lock()

print(
    "Whisper готов: http://127.0.0.1:47630",
    flush=True
)


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path in ("/", "/health"):
            self.answer(
                200,
                {
                    "status": "ready",
                    "model": REPO,
                    "device": "cuda"
                }
            )

        else:
            self.answer(
                404,
                {"error": "Not found"}
            )

    def do_POST(self):
        if self.path != "/transcribe":
            self.answer(
                404,
                {"error": "Not found"}
            )
            return

        try:
            size = int(
                self.headers.get("Content-Length", "0")
            )

            if size < 44 or size > 100 * 1024 * 1024:
                self.answer(
                    400,
                    {"error": "Некорректный размер WAV"}
                )
                return

            audio = self.rfile.read(size)

            with tempfile.NamedTemporaryFile(
                    suffix=".wav",
                    delete=False
            ) as temp:
                temp.write(audio)
                path = temp.name

            try:
                with lock:
                    segments, info = model.transcribe(
                        path,
                        language="ru",
                        task="transcribe",
                        beam_size=5,
                        vad_filter=True,
                        condition_on_previous_text=False,
                        initial_prompt=(
                            "Python, Java, JavaScript, GitHub, "
                            "git commit, git push, git pull, "
                            "Docker, Docker Compose, HashMap, "
                            "getUserName, IntelliJ IDEA, "
                            "ChatGPT, OpenAI, Rust, SQL, "
                            "Windows, VS Code, pull request"
                        )
                    )

                    raw_text = " ".join(
                        segment.text.strip()
                        for segment in segments
                    ).strip()

                    corrected_text = correct_technical_terms(
                        raw_text
                    )

                print(
                    "\nWhisper:",
                    raw_text,
                    flush=True
                )

                print(
                    "Исправлено:",
                    corrected_text,
                    flush=True
                )

                self.answer(
                    200,
                    {
                        "text": corrected_text,
                        "raw_text": raw_text,
                        "language": info.language
                    }
                )

            finally:
                os.unlink(path)

        except Exception as exc:
            print(
                "Ошибка распознавания:",
                repr(exc),
                flush=True
            )

            self.answer(
                500,
                {"error": str(exc)}
            )

    def answer(self, code, obj):
        body = json.dumps(
            obj,
            ensure_ascii=False
        ).encode("utf-8")

        self.send_response(code)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8"
        )

        self.send_header(
            "Content-Length",
            str(len(body))
        )

        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(
        (HOST, PORT),
        Handler
    )

    print(
        "Сервер ожидает голосовые запросы...",
        flush=True
    )

    server.serve_forever()
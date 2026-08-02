# Estadística de dispersión (para el paper)

reps=5 por celda. rps: mediana [min–max]. Fuente: raw-docker.csv. Arranque/imagen son deterministas por framework.

| Framework | Imagen MB | Arranque ms | RSS MB (med) | rps /plaintext med [min–max] | rps /json med [min–max] |
|---|---|---|---|---|---|
| jxmvc | 271.7 | 822 | 448.5 | 49062 [47198–49645] | 48720 [48389–49005] |
| spring | 299.9 | 1945 | 375.6 | 49462 [47865–49979] | 49896 [48789–50295] |
| quarkus | 295.9 | 606 | 431.2 | 55087 [54767–55552] | 54209 [53831–55391] |
| micronaut | 292.5 | 1154 | 331.4 | 50974 [50502–51922] | 50652 [50295–51444] |
| javalin | 286.4 | 369 | 424.6 | 54169 [53567–57131] | 53614 [52926–54148] |
| quarkus-native | 72.8 | 12 | 25.12 | 52699 [52488–53123] | 50073 [46614–52214] |

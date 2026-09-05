# Publicar en Maven Central

Para que alguien pueda usar Cero escribiendo tres líneas en su `pom.xml` en vez de clonar el
repositorio y compilarlo. Es lo único que separa al framework de poder usarse de verdad.

**Es gratis.** Maven Central lleva décadas siendo gratuito para proyectos abiertos: no se paga por
publicar ni por descargar, y no hay ningún plan de pago que haga falta.

**No tiene nada que ver con GitHub.** GitHub guarda el código; Central guarda el **artefacto
compilado**. Son dos sitios distintos y hacen falta los dos.

Cuatro pasos. La primera vez lleva media hora, casi toda esperando a que el DNS se propague. Las
siguientes son un solo comando.

---

## 1 · La cuenta y el dominio

Central no deja publicar bajo `dev.ginit.cero` a cualquiera: hay que demostrar que `ginit.dev` es
tuyo. Es lo que impide que alguien publique paquetes haciéndose pasar por otro.

1. Entra en **[central.sonatype.com](https://central.sonatype.com)** y crea la cuenta. Se puede
   con la de GitHub, que es lo más rápido.
2. Ve a **Namespaces** → *Add Namespace* y escribe `dev.ginit`.

   > Se registra `dev.ginit`, no `dev.ginit.cero`. Un *namespace* cubre todo lo que cuelgue de él,
   > así que con este quedan cubiertos los seis módulos de Cero y cualquier otro proyecto que
   > publiques bajo ese dominio en el futuro.

3. Te da un **código de verificación**, algo como `abc123xyz`. Hay que ponerlo como registro TXT
   en el DNS de `ginit.dev`.

   > **El DNS de `ginit.dev` está en Namecheap, no en Contabo.** Es fácil confundirlo porque
   > Contabo es donde corre el servidor —la IP 173.249.52.127— pero quien responde a las
   > preguntas del dominio es `dns1/dns2.registrar-servers.com`, que son los de Namecheap. El
   > registro va en su panel: **Domain List → ginit.dev → Advanced DNS → Add New Record**.
   >
   > Comprobable en cualquier momento con `dig +short NS ginit.dev`.

   ```text
   Tipo:   TXT Record
   Host:   @          (eso significa ginit.dev a secas)
   Value:  abc123xyz  (el que te dé Central, tal cual)
   TTL:    Automatic
   ```

   Ya hay un TXT ahí —el `v=spf1 …` del reenvío de correo— y **no hay que tocarlo**: un dominio
   puede tener varios TXT a la vez y se añade uno más.

4. Vuelve a Central y pulsa **Verify Namespace**. Si dice que no lo ve, espera: el DNS tarda entre
   diez minutos y unas horas. Se puede comprobar sin salir de la terminal:

   ```bash
   dig +short TXT ginit.dev
   ```

5. Cuando esté verificado, ve a tu perfil → **Generate User Token**. Te da un usuario y una
   contraseña que **no son los de tu cuenta**: son credenciales solo para publicar. Guárdalas,
   porque la contraseña se enseña una sola vez.

---

## 2 · La clave para firmar

Central exige que cada archivo vaya firmado criptográficamente. No es burocracia: es lo que impide
que alguien suba un `cero-core-0.6.0.jar` con una puerta trasera haciéndose pasar por ti. Tú firmas
con una clave privada que solo tienes tú; cualquiera puede comprobar la firma con la pública.

`gpg` ya está instalado. Genera la clave — **este comando lo tienes que correr tú**, porque la
contraseña es tuya y no debe pasar por ningún sitio:

```bash
gpg --full-generate-key
```

Responde:

| Pregunta | Qué poner |
|---|---|
| Tipo de clave | **9** (ECC, firma y cifrado) o **1** (RSA) — las dos valen |
| Curva / tamaño | El que sugiere por defecto |
| Validez | `0` — que no caduque, o tendrás que renovarla y volver a publicarla |
| Nombre | `Richar Andre Vilca-Solorzano` |
| Correo | El que uses para el proyecto |
| Contraseña | **Una que recuerdes.** Hace falta cada vez que publiques |

Luego mira el identificador de la clave:

```bash
gpg --list-secret-keys --keyid-format=long
```

Sale algo así, y lo que interesa es lo de después de la barra:

```text
sec   ed25519/A1B2C3D4E5F6A7B8 2026-09-05 [SC]
                └──────┬──────┘
                   este es
```

Y **publícala**, que es lo que permite a Central comprobar tus firmas:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys A1B2C3D4E5F6A7B8
```

---

## 3 · Las credenciales

Van en `~/.m2/settings.xml`, que es un archivo de tu máquina y **nunca entra en el repositorio**.
Si no existe, se crea:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>EL-USUARIO-DEL-TOKEN</username>
      <password>LA-CONTRASEÑA-DEL-TOKEN</password>
    </server>
  </servers>
</settings>
```

El `<id>central</id>` no es un nombre libre: tiene que ser exactamente ese, porque es el que espera
el plugin de publicación configurado en `java/pom.xml`.

Deja el archivo solo para ti:

```bash
chmod 600 ~/.m2/settings.xml
```

---

## 4 · Publicar

```bash
cd java
mvn -Ppublicar deploy
```

Va a pedirte la contraseña de la clave GPG. Tarda unos minutos: firma cada uno de los archivos de
los seis módulos —el jar, el de fuentes, el de javadoc y el pom— y los sube.

**No se publica solo.** El perfil lleva `autoPublish` en falso a propósito: sube al portal y
espera. Entra en [central.sonatype.com](https://central.sonatype.com) → *Deployments*, mira que
esté todo, y pulsa **Publish**.

Ese botón es el punto de no retorno: **una versión publicada en Central no se puede borrar ni
rehacer**. Si sale mal, la única salida es publicar una 0.6.1. Por eso conviene mirar antes.

Tarda entre diez minutos y un par de horas en aparecer en las búsquedas.

---

## Comprobarlo

```bash
curl -s "https://search.maven.org/solrsearch/select?q=g:dev.ginit.cero&wt=json" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['numFound'], 'artefactos')"
```

Y la prueba de verdad, en una carpeta vacía y sin nada en `~/.m2`:

```xml
<dependency>
  <groupId>dev.ginit.cero</groupId>
  <artifactId>cero-core</artifactId>
  <version>0.6.0</version>
</dependency>
```

---

## Qué se publica y qué no

Se publican los **seis módulos que son bibliotecas**: `cero-http`, `cero-core`, `cero-view`,
`cero-data`, `cero-adapter-servlet` y `cero-launcher`.

**`ejemplo` y `cero-web` no.** Están marcados con `maven.deploy.skip` porque son aplicaciones —la
demostración y este sitio— y nadie las va a declarar como dependencia.

## Si algo falla

| Lo que dice | Qué pasa |
|---|---|
| `401 Unauthorized` | El usuario o la contraseña del `settings.xml`. Son los del **token**, no los de la cuenta |
| `Namespace not allowed` | El dominio no está verificado todavía, o registraste `dev.ginit.cero` en vez de `dev.ginit` |
| `gpg: signing failed` | Falta la clave o la contraseña. `gpg --list-secret-keys` para ver si está |
| `Missing signature` en el portal | La clave no está publicada en el servidor de claves; repite el `--send-keys` |
| `Invalid POM: missing description` | No debería pasar: los seis módulos la tienen y se comprobó |

## Para la próxima versión

Ya no hay que repetir los pasos 1, 2 y 3. Es subir la versión, y:

```bash
cd java && mvn -Ppublicar deploy
```

Ensayarlo antes sin firmar ni subir nada:

```bash
mvn -Ppublicar -Dgpg.skip=true verify
```

Eso genera los jar de fuentes y javadoc de los seis módulos y falla si falta algo de lo que Central
exige. Es lo que se corrió al preparar la 0.6.0.

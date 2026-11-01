import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
RUTA = os.path.join(AQUI, "comentarios.py")


def cargar():
    espec = importlib.util.spec_from_file_location("comentarios", RUTA)
    modulo = importlib.util.module_from_spec(espec)
    espec.loader.exec_module(modulo)
    return modulo


def quitar(texto, extension):
    return cargar().procesar(texto, extension)[0]


def ejecutar(*argumentos):
    return subprocess.run(
        [sys.executable, "-I", RUTA, *argumentos],
        capture_output=True,
        text=True,
    )


class PruebasComentarios(unittest.TestCase):
    def test_url_en_cadena_se_conserva(self):
        entrada = 'String u = "http://x/*y*/";\n'
        self.assertEqual(quitar(entrada, ".java"), entrada)

    def test_caracter_barra_se_conserva(self):
        self.assertEqual(quitar("char c = '/'; // fin\n", ".java"), "char c = '/';\n")

    def test_bloque_de_texto_java(self):
        entrada = 'String t = """\n    hola // no\n    """;\n'
        self.assertEqual(quitar(entrada, ".java"), entrada)

    def test_cadena_cruda_rust(self):
        entrada = 'let s = r#"a // b"#;\n'
        self.assertEqual(quitar(entrada, ".rs"), entrada)

    def test_lifetime_rust(self):
        self.assertEqual(
            quitar("fn f<'a>(x: &'a str) {} // c\n", ".rs"),
            "fn f<'a>(x: &'a str) {}\n",
        )

    def test_caracteres_rust(self):
        entrada = "let a = '\"'; let b = '\\''; let c = b'x'; // c\n"
        esperado = "let a = '\"'; let b = '\\''; let c = b'x';\n"
        self.assertEqual(quitar(entrada, ".rs"), esperado)

    def test_bloque_anidado_rust(self):
        self.assertEqual(quitar("/* a /* b */ c */ x", ".rs"), "x")

    def test_linea_entera_desaparece(self):
        entrada = "a\n\n// c\n\nb\n// d\nc\n"
        self.assertEqual(quitar(entrada, ".java"), "a\n\nb\nc\n")

    def test_javadoc_desaparece(self):
        entrada = (
            "class A {\n"
            "    /**\n"
            "     * Hace algo.\n"
            "     */\n"
            "    void f() {}\n"
            "}\n"
        )
        self.assertEqual(quitar(entrada, ".java"), "class A {\n    void f() {}\n}\n")

    def test_python_comentario_y_docstring(self):
        entrada = (
            "#!/usr/bin/env python3\n"
            "# x\n"
            "def f():\n"
            '    """doc"""\n'
            '    s = "#no"  # fuera\n'
            "    return s\n"
        )
        esperado = (
            "#!/usr/bin/env python3\n"
            "def f():\n"
            '    s = "#no"\n'
            "    return s\n"
        )
        self.assertEqual(quitar(entrada, ".py"), esperado)

    def test_python_docstring_unico_queda_con_pass(self):
        entrada = 'class A:\n    """solo"""\n'
        self.assertEqual(quitar(entrada, ".py"), "class A:\n    pass\n")

    def test_directivas_cpp_se_conservan(self):
        entrada = "#pragma once\n#include <x>\nint a; // c\n"
        self.assertEqual(
            quitar(entrada, ".hpp"), "#pragma once\n#include <x>\nint a;\n"
        )

    def test_separador_de_digitos_cpp(self):
        entrada = "int n = 1'000'000; // c\nchar c = '/';\n"
        self.assertEqual(quitar(entrada, ".cpp"), "int n = 1'000'000;\nchar c = '/';\n")

    def test_cadena_cruda_cpp(self):
        entrada = 'auto s = R"x(a // b)x"; /* c */\n'
        self.assertEqual(quitar(entrada, ".cpp"), 'auto s = R"x(a // b)x";\n')

    def test_byte_nulo_en_cadena_se_conserva(self):
        entrada = 'String s = "a\x00b"; // fin\n'
        self.assertEqual(quitar(entrada, ".java"), 'String s = "a\x00b";\n')
        self.assertEqual(quitar('String s = "a\x00b";\n', ".java"), 'String s = "a\x00b";\n')

    def test_bloque_en_medio_de_una_linea(self):
        self.assertEqual(quitar("int /* c */ x;\n", ".java"), "int x;\n")
        self.assertEqual(quitar("int/**/x;\n", ".java"), "int x;\n")

    def test_fin_de_linea_windows(self):
        self.assertEqual(quitar("a // c\r\nb\r\n", ".java"), "a\r\nb\r\n")

    def test_lineas_encontradas(self):
        _, lineas = cargar().procesar("a\n// c\nb /* d\n e */\n", ".java")
        self.assertEqual(lineas, [2, 3])

    def test_verificar_sale_con_uno(self):
        with tempfile.TemporaryDirectory() as tmp:
            archivo = os.path.join(tmp, "A.java")
            with open(archivo, "w", encoding="utf-8") as f:
                f.write("class A {} // c\n")
            r = ejecutar("--verificar", tmp)
            self.assertEqual(r.returncode, 1)
            self.assertIn(archivo + ":1", r.stdout)
            with open(archivo, encoding="utf-8") as f:
                self.assertEqual(f.read(), "class A {} // c\n")

    def test_verificar_limpio_sale_con_cero_e_ignora_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "A.java"), "w", encoding="utf-8") as f:
                f.write("class A {}\n")
            os.makedirs(os.path.join(tmp, "target"))
            with open(os.path.join(tmp, "target", "B.java"), "w", encoding="utf-8") as f:
                f.write("// c\n")
            self.assertEqual(ejecutar("--verificar", tmp).returncode, 0)

    def test_quitar_reescribe_el_archivo(self):
        with tempfile.TemporaryDirectory() as tmp:
            archivo = os.path.join(tmp, "a.rs")
            with open(archivo, "w", encoding="utf-8") as f:
                f.write("fn f() {} // c\n")
            self.assertEqual(ejecutar("--quitar", tmp).returncode, 0)
            with open(archivo, encoding="utf-8") as f:
                self.assertEqual(f.read(), "fn f() {}\n")


if __name__ == "__main__":
    unittest.main()

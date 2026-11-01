package cero.core;

import cero.http.HttpException;
import cero.http.HttpMethod;
import cero.http.Part;
import cero.http.Session;

import java.util.List;

public abstract class Controller {

    protected final Context ctx() {
        return Current.context();
    }

    protected final String path() {
        return ctx().path();
    }

    protected final HttpMethod method() {
        return ctx().method();
    }

    protected final String param(String nombre) {
        return ctx().pathVariable(nombre);
    }

    protected final String query(String nombre) {
        return ctx().query(nombre);
    }

    protected final String query(String nombre, String porDefecto) {
        return ctx().query(nombre, porDefecto);
    }

    protected final String form(String nombre) {
        return ctx().form(nombre);
    }

    protected final String header(String nombre) {
        return ctx().header(nombre);
    }

    protected final String cookie(String nombre) {
        return ctx().cookie(nombre);
    }

    protected final <T> T body(Class<T> tipo) {
        return ctx().body(tipo);
    }

    protected final List<Part> parts() {
        return ctx().parts();
    }

    protected final Session session() {
        return ctx().session();
    }

    protected final Principal usuario() {
        return ctx().principal();
    }

    protected final boolean autenticado() {
        return ctx().authenticated();
    }

    protected final Result json(Object valor) {
        return Result.json(valor);
    }

    protected final Result text(String cuerpo) {
        return Result.text(cuerpo);
    }

    protected final Result html(String cuerpo) {
        return Result.html(cuerpo);
    }

    protected final Result redirect(String destino) {
        return Result.redirect(destino);
    }

    protected final Result created(Object valor) {
        return Result.created(valor);
    }

    protected final Result noContent() {
        return Result.noContent();
    }

    protected final Result status(int codigo, String cuerpo) {
        return Result.status(codigo, cuerpo);
    }

    protected final HttpException fallo(int codigo, String mensaje) {
        return new HttpException(codigo, mensaje);
    }
}

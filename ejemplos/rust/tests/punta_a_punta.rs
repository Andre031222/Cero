use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process::{Child, Command};
use std::thread;
use std::time::{Duration, Instant};

struct Proceso(Child);

impl Drop for Proceso {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn puerto_libre() -> u16 {
    TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

fn arrancar(puerto: u16) -> Proceso {
    let hijo = Command::new(env!("CARGO_BIN_EXE_ejemplo"))
        .env("PUERTO", puerto.to_string())
        .spawn()
        .unwrap();
    let proceso = Proceso(hijo);
    let limite = Instant::now() + Duration::from_secs(10);
    while TcpStream::connect(("127.0.0.1", puerto)).is_err() {
        assert!(Instant::now() < limite, "el ejemplo no arrancó en :{puerto}");
        thread::sleep(Duration::from_millis(20));
    }
    proceso
}

fn pedir(puerto: u16, metodo: &str, camino: &str, cuerpo: &str) -> (u16, String) {
    let mut flujo = TcpStream::connect(("127.0.0.1", puerto)).unwrap();
    let peticion = format!(
        "{metodo} {camino} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{cuerpo}",
        cuerpo.len()
    );
    flujo.write_all(peticion.as_bytes()).unwrap();
    let mut respuesta = String::new();
    flujo.read_to_string(&mut respuesta).unwrap();
    let estado = respuesta[9..12].parse().unwrap();
    let (_, resto) = respuesta.split_once("\r\n\r\n").unwrap();
    (estado, resto.into())
}

#[test]
fn las_tres_rutas() {
    let puerto = puerto_libre();
    let _ejemplo = arrancar(puerto);
    assert_eq!(pedir(puerto, "GET", "/", ""), (200, r#"{"hola":"mundo"}"#.into()));
    assert_eq!(pedir(puerto, "GET", "/saludo/ana", ""), (200, r#"{"saludo":"hola, ana"}"#.into()));
    assert_eq!(pedir(puerto, "POST", "/eco", r#"{"a":[1,2]}"#), (200, r#"{"a":[1,2]}"#.into()));
    assert_eq!(pedir(puerto, "POST", "/eco", "no es json").0, 400);
    assert_eq!(pedir(puerto, "HEAD", "/", ""), (200, String::new()));
}

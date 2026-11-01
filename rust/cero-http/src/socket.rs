use std::io::{self, ErrorKind, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy)]
pub struct Plazos {
    pub sin_avance: Duration,
    pub escritura: Duration,
    pub inactividad: Duration,
}

impl Default for Plazos {
    fn default() -> Plazos {
        Plazos {
            sin_avance: Duration::from_secs(30),
            escritura: Duration::from_secs(60),
            inactividad: Duration::from_secs(120),
        }
    }
}

impl Plazos {
    pub fn validos(&self) -> bool {
        !self.sin_avance.is_zero() && !self.escritura.is_zero() && !self.inactividad.is_zero()
    }
}

const TROZO: usize = 16 * 1024;
const PIEZA: usize = 4 * 1024;

pub fn mandar_todo(socket: &mut TcpStream, datos: &[u8], plazos: &Plazos) -> io::Result<()> {
    for trozo in datos.chunks(TROZO) {
        let hasta = Instant::now() + plazos.escritura;
        let mut resto = trozo;
        while !resto.is_empty() {
            let queda = hasta.saturating_duration_since(Instant::now());
            if queda < Duration::from_millis(1) {
                return Err(ErrorKind::TimedOut.into());
            }
            socket.set_write_timeout(Some(queda.min(plazos.sin_avance)))?;
            match socket.write(&resto[..resto.len().min(PIEZA)]) {
                Ok(0) => return Err(ErrorKind::WriteZero.into()),
                Ok(n) => resto = &resto[n..],
                Err(e) if e.kind() == ErrorKind::Interrupted => {}
                Err(e) => return Err(e),
            }
        }
    }
    Ok(())
}

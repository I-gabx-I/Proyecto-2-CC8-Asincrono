/** Panel de depuración: pares etiqueta -> valor. */
export function mostrarPanel(elemento, datos) {
  const titulo = document.createElement('h2');
  titulo.textContent = 'Depuración';
  const tabla = document.createElement('table');
  for (const [etiqueta, valor] of Object.entries(datos)) {
    const fila = tabla.insertRow();
    fila.insertCell().textContent = etiqueta;
    fila.insertCell().textContent = valor;
  }
  elemento.replaceChildren(titulo, tabla);
}
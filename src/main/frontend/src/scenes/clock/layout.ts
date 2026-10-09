export interface Box {
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

/** A random position that keeps the face fully on screen (spreads OLED wear). */
export function placeWithin(area: Box, face: Box, random: () => number): Point {
  const maxX = Math.max(0, area.width - face.width);
  const maxY = Math.max(0, area.height - face.height);
  return { x: Math.floor(random() * (maxX + 1)), y: Math.floor(random() * (maxY + 1)) };
}

/** Moves the face up to `maxStep` pixels in each direction, staying on screen. */
export function drift(position: Point, area: Box, face: Box, random: () => number, maxStep = 8): Point {
  const step = () => Math.round((random() * 2 - 1) * maxStep);
  const clamp = (value: number, max: number) => Math.min(Math.max(value, 0), Math.max(0, max));
  return {
    x: clamp(position.x + step(), area.width - face.width),
    y: clamp(position.y + step(), area.height - face.height),
  };
}

const pad = (n: number) => String(n).padStart(2, '0');

export function formatDigital(date: Date, use24Hour: boolean): { time: string; suffix: string } {
  const hours = date.getHours();
  const minutes = pad(date.getMinutes());
  if (use24Hour) {
    return { time: `${pad(hours)}:${minutes}`, suffix: '' };
  }
  const hours12 = hours % 12 === 0 ? 12 : hours % 12;
  return { time: `${hours12}:${minutes}`, suffix: hours < 12 ? 'AM' : 'PM' };
}

/** Clockwise angles in degrees from 12 o'clock. */
export function handAngles(date: Date): { hour: number; minute: number; second: number } {
  const seconds = date.getSeconds();
  const minutes = date.getMinutes() + seconds / 60;
  const hours = (date.getHours() % 12) + minutes / 60;
  return { hour: hours * 30, minute: minutes * 6, second: seconds * 6 };
}

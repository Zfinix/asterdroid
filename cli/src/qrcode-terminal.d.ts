declare module "qrcode-terminal" {
  const qrcode: { generate(text: string, options: { small?: boolean }, done: (out: string) => void): void };
  export default qrcode;
}

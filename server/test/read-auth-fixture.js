export const TEST_READ_TOKEN = "test-read-token";

export const TEST_READ_AUTH = {
  bots: [
    {
      id: "default",
      token: TEST_READ_TOKEN,
      tools: null,
      coords: true,
    },
  ],
};

export function readHeaders(extra = {}) {
  return {
    "X-PhonePi-Read-Token": TEST_READ_TOKEN,
    ...extra,
  };
}

import { createUpgradeRequestForm, toUpgradeRequest } from './upgrade-request-form';

describe('upgrade request form', () => {
  it('requires a non-blank userId', () => {
    const form = createUpgradeRequestForm({ userId: '   ' });
    expect(form.controls.userId.valid).toBe(false);

    form.controls.userId.setValue('u1');
    expect(form.valid).toBe(true);
  });

  it('rejects a malformed parent email and a fractional age', () => {
    const form = createUpgradeRequestForm({ userId: 'u1', parentEmail: 'not-an-email', age: 19.5 });
    expect(form.controls.parentEmail.valid).toBe(false);
    expect(form.controls.age.valid).toBe(false);
  });

  it('leaves eligibility rules to the backend, so an ineligible request is still valid', () => {
    const form = createUpgradeRequestForm({ userId: 'u1', userName: '', age: 12, balance: 1 });
    expect(form.valid).toBe(true);
  });

  it('maps form values to a request body, trimming text and sending blanks as null', () => {
    const request = toUpgradeRequest({
      userId: ' u1 ',
      userName: 'Ann',
      age: 21,
      balance: '45.5' as unknown as number,
      parentEmail: '  ',
    });

    expect(request).toEqual({
      userId: 'u1',
      userName: 'Ann',
      age: 21,
      balance: 45.5,
      parentEmail: null,
    });
  });

  it('keeps an empty user name as an empty string so the backend records the rule failure', () => {
    expect(toUpgradeRequest({ userId: 'u1', userName: '' }).userName).toBe('');
  });
});

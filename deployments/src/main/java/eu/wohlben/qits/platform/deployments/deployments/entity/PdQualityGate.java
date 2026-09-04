package eu.wohlben.qits.platform.deployments.deployments.entity;

/**
 * Whether a {@link PdDeploymentRequest} may become a deployment.
 *
 * <p><b>Two words, and today one of them is unreachable.</b> The gate is a placeholder: every
 * request this component accepts is {@link #MET} the moment it is written, because a released
 * version has already passed qits-ci's own pipeline and this component has nothing further to ask.
 * {@link #UNMET} is modelled anyway, and that is the point of the type existing at all — the day a
 * real gate has an opinion (a userflow suite, a manual approval, a soak window), it writes that word
 * onto a row whose readers already exist, instead of arriving as a migration over live history.
 *
 * <p><b>It is a varchar with no check constraint</b> (V1's rule for every enum column in this
 * schema), so a third word costs no migration. Whoever adds one adds it here first: this enum owns
 * validity at every Java write path, and the database deliberately enumerates nothing.
 */
public enum PdQualityGate {

  /**
   * Nothing has decided yet, or something decided no. The request stands and no deployment was
   * queued for it; {@code gate_detail} says which of the two and why.
   *
   * <p>Unwritten today. It is not dead code — it is the state every future gate starts a request in
   * and the state a refusal ends one in, and the readers of this column are written against both
   * words already.
   */
  UNMET,

  /**
   * The request may proceed, and a deployment was queued for it. What the placeholder writes on
   * every request, immediately.
   */
  MET
}

// Copyright 2018, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import static oracle.kubernetes.operator.VersionConstants.DEFAULT_OPERATOR_VERSION;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1beta1CustomResourceDefinition;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionNames;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionSpec;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionStatus;
import io.kubernetes.client.models.V1beta1CustomResourceDefinitionVersion;
import io.kubernetes.client.models.V1beta1CustomResourceSubresourceScale;
import io.kubernetes.client.models.V1beta1CustomResourceSubresources;
import io.kubernetes.client.models.V1beta1CustomResourceValidation;
import io.kubernetes.client.models.V1beta1JSONSchemaProps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import oracle.kubernetes.json.SchemaGenerator;
import oracle.kubernetes.operator.KubernetesConstants;
import oracle.kubernetes.operator.LabelConstants;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.steps.DefaultResponseStep;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import oracle.kubernetes.weblogic.domain.model.DomainStatus;

/** Helper class to ensure Domain CRD is created. */
public class CRDHelper {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private static final CRDComparator COMPARATOR = new CRDComparatorImpl();

  private CRDHelper() {}

  /**
   * Factory for {@link Step} that creates Domain CRD.
   *
   * @param version Version of the Kubernetes API Server
   * @param next Next step
   * @return Step for creating Domain custom resource definition
   */
  public static Step createDomainCRDStep(KubernetesVersion version, Step next) {
    return new CRDStep(version, next);
  }

  static class CRDStep extends Step {
    CRDContext context;

    CRDStep(KubernetesVersion version, Step next) {
      super(next);
      context = new CRDContext(version, this);
    }

    @Override
    public NextAction apply(Packet packet) {
      return doNext(context.verifyCRD(getNext()), packet);
    }
  }

  static class CRDContext {
    private final Step conflictStep;
    private final V1beta1CustomResourceDefinition model;
    private final KubernetesVersion version;

    CRDContext(KubernetesVersion version, Step conflictStep) {
      this.version = version;
      this.conflictStep = conflictStep;
      this.model = createModel(version);
    }

    static V1beta1CustomResourceDefinition createModel(KubernetesVersion version) {
      return new V1beta1CustomResourceDefinition()
          .apiVersion("apiextensions.k8s.io/v1beta1")
          .kind("CustomResourceDefinition")
          .metadata(createMetadata())
          .spec(createSpec(version));
    }

    static V1ObjectMeta createMetadata() {
      return new V1ObjectMeta()
          .name(KubernetesConstants.CRD_NAME)
          .putLabelsItem(LabelConstants.RESOURCE_VERSION_LABEL, DEFAULT_OPERATOR_VERSION);
    }

    static V1beta1CustomResourceDefinitionSpec createSpec(KubernetesVersion version) {
      V1beta1CustomResourceDefinitionSpec spec =
          new V1beta1CustomResourceDefinitionSpec()
              .group(KubernetesConstants.DOMAIN_GROUP)
              .version(KubernetesConstants.DOMAIN_VERSION)
              .versions(getCRDVersions())
              .scope("Namespaced")
              .names(getCRDNames())
              .validation(createSchemaValidation());
      if (version.isCRDSubresourcesSupported()) {
        spec.setSubresources(
            new V1beta1CustomResourceSubresources()
                .scale(
                    new V1beta1CustomResourceSubresourceScale()
                        .specReplicasPath(".spec.replicas")
                        .statusReplicasPath(".status.replicas")));
        // Remove status for now because seeing status not updated on some k8s environments
        // Consider adding this only for K8s version 1.13+
        // See the note in KubernetesVersion
        // .status(new HashMap<String, Object>()));
      }
      return spec;
    }

    static List<V1beta1CustomResourceDefinitionVersion> getCRDVersions() {
      List<V1beta1CustomResourceDefinitionVersion> versions =
          Arrays.stream(KubernetesConstants.DOMAIN_ALTERNATE_VERSIONS)
              .map(e -> new V1beta1CustomResourceDefinitionVersion().name(e).served(true))
              .collect(Collectors.toList());
      versions.add(
          0, // must be first
          new V1beta1CustomResourceDefinitionVersion()
              .name(KubernetesConstants.DOMAIN_VERSION)
              .served(true)
              .storage(true));
      return versions;
    }

    static V1beta1CustomResourceDefinitionNames getCRDNames() {
      return new V1beta1CustomResourceDefinitionNames()
          .plural(KubernetesConstants.DOMAIN_PLURAL)
          .singular(KubernetesConstants.DOMAIN_SINGULAR)
          .kind(KubernetesConstants.DOMAIN)
          .shortNames(Collections.singletonList(KubernetesConstants.DOMAIN_SHORT));
    }

    static V1beta1CustomResourceValidation createSchemaValidation() {
      return new V1beta1CustomResourceValidation().openAPIV3Schema(createOpenAPIV3Schema());
    }

    static V1beta1JSONSchemaProps createOpenAPIV3Schema() {
      Gson gson = new Gson();
      JsonElement jsonElementSpec =
          gson.toJsonTree(createSchemaGenerator().generate(DomainSpec.class));
      V1beta1JSONSchemaProps spec = gson.fromJson(jsonElementSpec, V1beta1JSONSchemaProps.class);
      JsonElement jsonElementStatus =
          gson.toJsonTree(createSchemaGenerator().generate(DomainStatus.class));
      V1beta1JSONSchemaProps status =
          gson.fromJson(jsonElementStatus, V1beta1JSONSchemaProps.class);
      return new V1beta1JSONSchemaProps()
          .putPropertiesItem("spec", spec)
          .putPropertiesItem("status", status);
    }

    static SchemaGenerator createSchemaGenerator() {
      SchemaGenerator generator = new SchemaGenerator();
      generator.setIncludeAdditionalProperties(false);
      generator.setSupportObjectReferences(false);
      generator.setIncludeDeprecated(true);
      generator.setIncludeSchemaReference(false);
      return generator;
    }

    Step verifyCRD(Step next) {
      return new CallBuilder()
          .readCustomResourceDefinitionAsync(
              model.getMetadata().getName(), createReadResponseStep(next));
    }

    ResponseStep<V1beta1CustomResourceDefinition> createReadResponseStep(Step next) {
      return new ReadResponseStep(next);
    }

    class ReadResponseStep extends DefaultResponseStep<V1beta1CustomResourceDefinition> {
      ReadResponseStep(Step next) {
        super(next);
      }

      @Override
      public NextAction onSuccess(
          Packet packet, CallResponse<V1beta1CustomResourceDefinition> callResponse) {
        V1beta1CustomResourceDefinition existingCRD = callResponse.getResult();
        if (existingCRD == null) {
          return doNext(createCRD(getNext()), packet);
        } else if (isOutdatedCRD(existingCRD)) {
          return doNext(updateCRD(getNext(), existingCRD), packet);
        } else if (!existingCRDContainsVersion(existingCRD)) {
          return doNext(updateExistingCRD(getNext(), existingCRD), packet);
        } else {
          return doNext(packet);
        }
      }
    }

    Step createCRD(Step next) {
      return new CallBuilder()
          .createCustomResourceDefinitionAsync(model, createCreateResponseStep(next));
    }

    ResponseStep<V1beta1CustomResourceDefinition> createCreateResponseStep(Step next) {
      return new CreateResponseStep(next);
    }

    private class CreateResponseStep extends ResponseStep<V1beta1CustomResourceDefinition> {
      CreateResponseStep(Step next) {
        super(next);
      }

      @Override
      public NextAction onFailure(
          Packet packet, CallResponse<V1beta1CustomResourceDefinition> callResponse) {
        return super.onFailure(conflictStep, packet, callResponse);
      }

      @Override
      public NextAction onSuccess(
          Packet packet, CallResponse<V1beta1CustomResourceDefinition> callResponse) {
        LOGGER.info(MessageKeys.CREATING_CRD, callResponse);
        return doNext(packet);
      }
    }

    private boolean isOutdatedCRD(V1beta1CustomResourceDefinition existingCRD) {
      return COMPARATOR.isOutdatedCRD(existingCRD, this.model);
    }

    private boolean existingCRDContainsVersion(V1beta1CustomResourceDefinition existingCRD) {
      List<V1beta1CustomResourceDefinitionVersion> versions = existingCRD.getSpec().getVersions();
      boolean found = false;
      if (versions != null) {
        for (V1beta1CustomResourceDefinitionVersion v : versions) {
          if (KubernetesConstants.DOMAIN_VERSION.equals(v.getName())) {
            found = true;
            break;
          }
        }
      }

      return found;
    }

    Step updateExistingCRD(Step next, V1beta1CustomResourceDefinition existingCRD) {
      existingCRD
          .getSpec()
          .addVersionsItem(
              new V1beta1CustomResourceDefinitionVersion()
                  .name(KubernetesConstants.DOMAIN_VERSION)
                  .served(true));

      return new CallBuilder()
          .replaceCustomResourceDefinitionAsync(
              existingCRD.getMetadata().getName(), existingCRD, createReplaceResponseStep(next));
    }

    Step updateCRD(Step next, V1beta1CustomResourceDefinition existingCRD) {
      model.getMetadata().setResourceVersion(existingCRD.getMetadata().getResourceVersion());

      // preserve any stored versions
      V1beta1CustomResourceDefinitionStatus status = existingCRD.getStatus();
      if (status != null) {
        List<String> storedVersions = status.getStoredVersions();
        if (storedVersions != null) {
          List<ResourceVersion> modelVersions = getVersions(model);
          for (String sv : storedVersions) {
            if (!modelVersions.contains(new ResourceVersion(sv))) {
              model
                  .getSpec()
                  .addVersionsItem(
                      new V1beta1CustomResourceDefinitionVersion().name(sv).served(true));
            }
          }
        }
      }

      return new CallBuilder()
          .replaceCustomResourceDefinitionAsync(
              model.getMetadata().getName(), model, createReplaceResponseStep(next));
    }

    ResponseStep<V1beta1CustomResourceDefinition> createReplaceResponseStep(Step next) {
      return new ReplaceResponseStep(next);
    }

    private class ReplaceResponseStep extends ResponseStep<V1beta1CustomResourceDefinition> {
      ReplaceResponseStep(Step next) {
        super(next);
      }

      @Override
      public NextAction onFailure(
          Packet packet, CallResponse<V1beta1CustomResourceDefinition> callResponse) {
        return super.onFailure(conflictStep, packet, callResponse);
      }

      @Override
      public NextAction onSuccess(
          Packet packet, CallResponse<V1beta1CustomResourceDefinition> callResponse) {
        LOGGER.info(MessageKeys.CREATING_CRD, callResponse);
        return doNext(packet);
      }
    }
  }

  private static List<ResourceVersion> getVersions(V1beta1CustomResourceDefinition crd) {
    List<ResourceVersion> versions = new ArrayList<>();
    String v = crd.getSpec().getVersion();
    if (v != null) {
      versions.add(new ResourceVersion(v));
    }
    List<V1beta1CustomResourceDefinitionVersion> vs = crd.getSpec().getVersions();
    if (vs != null) {
      for (V1beta1CustomResourceDefinitionVersion vi : vs) {
        versions.add(new ResourceVersion(vi.getName()));
      }
    }

    return versions;
  }

  interface CRDComparator {
    boolean isOutdatedCRD(
        V1beta1CustomResourceDefinition actual, V1beta1CustomResourceDefinition expected);
  }

  static class CRDComparatorImpl implements CRDComparator {
    @Override
    public boolean isOutdatedCRD(
        V1beta1CustomResourceDefinition actual, V1beta1CustomResourceDefinition expected) {
      ResourceVersion current = new ResourceVersion(KubernetesConstants.DOMAIN_VERSION);
      List<ResourceVersion> actualVersions = getVersions(actual);

      for (ResourceVersion v : actualVersions) {
        if (!isLaterOrEqual(v, current)) {
          return false;
        }
      }

      return getSchemaValidation(actual) == null
          || !getSchemaValidation(expected).equals(getSchemaValidation(actual))
          || !getSchemaSubresources(expected).equals(getSchemaSubresources(actual));
      // Similarly, we will later want to check:
      // VersionHelper.matchesResourceVersion(existingCRD.getMetadata(), DEFAULT_OPERATOR_VERSION)
    }

    // true, if version is later than base
    private boolean isLaterOrEqual(ResourceVersion base, ResourceVersion version) {
      if (version.getVersion() != base.getVersion()) {
        return version.getVersion() >= base.getVersion();
      }

      if (version.getPrerelease() == null) {
        if (base.getPrerelease() != null) {
          return true;
        }
      } else if (!version.getPrerelease().equals(base.getPrerelease())) {
        if (base.getPrerelease() == null) {
          return false;
        }
        return "alpha".equals(base.getPrerelease());
      }

      if (version.getPrereleaseVersion() == null) {
        return base.getPrereleaseVersion() == null;
      } else if (base.getPrereleaseVersion() == null) {
        return true;
      }
      return version.getPrereleaseVersion() >= base.getPrereleaseVersion();
    }

    private V1beta1JSONSchemaProps getSchemaValidation(V1beta1CustomResourceDefinition crd) {
      if (crd != null && crd.getSpec() != null && crd.getSpec().getValidation() != null) {
        return crd.getSpec().getValidation().getOpenAPIV3Schema();
      }
      return null;
    }

    private V1beta1CustomResourceSubresources getSchemaSubresources(
        V1beta1CustomResourceDefinition crd) {
      if (crd != null && crd.getSpec() != null) {
        return crd.getSpec().getSubresources();
      }
      return null;
    }
  }
}

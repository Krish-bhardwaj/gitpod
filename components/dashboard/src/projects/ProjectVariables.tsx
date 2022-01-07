/**
 * Copyright (c) 2021 Gitpod GmbH. All rights reserved.
 * Licensed under the GNU Affero General Public License (AGPL).
 * See License-AGPL.txt in the project root for license information.
 */

import { Project, ProjectEnvVar } from "@gitpod/gitpod-protocol";
import { useContext, useEffect, useState } from "react";
import CheckBox from "../components/CheckBox";
import InfoBox from "../components/InfoBox";
import { Item, ItemField, ItemsList } from "../components/ItemsList";
import Modal from "../components/Modal";
import { getGitpodService } from "../service/service";
import { ProjectContext } from "./project-context";
import { ProjectSettingsPage } from "./ProjectSettings";

export default function () {
    const { project } = useContext(ProjectContext);
    const [ envVars, setEnvVars ] = useState<ProjectEnvVar[]>([]);
    const [ showAddVariableModal, setShowAddVariableModal ] = useState<boolean>(false);

    const updateEnvVars = async () => {
        if (!project) {
            return;
        }
        const vars = await getGitpodService().server.getProjectEnvironmentVariables(project.id);
        const sortedVars = vars.sort((a, b) => a.name.toLowerCase() > b.name.toLowerCase() ? 1 : -1);
        setEnvVars(sortedVars);
    }

    useEffect(() => {
        updateEnvVars();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [project]);

    const deleteEnvVar = async (variableId: string) => {
        await getGitpodService().server.deleteProjectEnvironmentVariable(variableId);
        updateEnvVars();
    }

    return <ProjectSettingsPage project={project}>
        {showAddVariableModal && <AddVariableModal project={project} onClose={() => { updateEnvVars(); setShowAddVariableModal(false); }} />}
        <div className="mb-2 flex">
            <div className="flex-grow">
                <h3>Project Variables</h3>
                <h2 className="text-gray-500">Manage environment variables for your project.</h2>
            </div>
            {envVars.length > 0 && <button onClick={() => setShowAddVariableModal(true)}>Add Variable</button>}
        </div>
        {envVars.length === 0
            ? <div className="bg-gray-100 dark:bg-gray-800 rounded-xl w-full py-28 flex flex-col items-center">
                <h3 className="text-center pb-3 text-gray-500 dark:text-gray-400">No Environment Variables</h3>
                <button onClick={() => setShowAddVariableModal(true)}>New Variable</button>
            </div>
            : <>
                <ItemsList>
                    <Item header={true} className="grid grid-cols-5 items-center">
                        <ItemField>Name</ItemField>
                        <ItemField>Value</ItemField>
                        <ItemField>In Prebuilds?</ItemField>
                        <ItemField>In Workspaces?</ItemField>
                        <ItemField></ItemField>
                    </Item>
                    {envVars.map(variable => {
                        return <Item className="grid grid-cols-5 items-center">
                            <ItemField>{variable.name}</ItemField>
                            <ItemField>****</ItemField>
                            <ItemField>Visible</ItemField>
                            <ItemField>{variable.censored ? 'Censored' : 'Visible'}</ItemField>
                            <ItemField className="text-right"><button onClick={() => deleteEnvVar(variable.id)}>x</button></ItemField>
                        </Item>
                    })}
                </ItemsList>
            </>
        }
    </ProjectSettingsPage>;
}

function AddVariableModal(props: { project?: Project, onClose: () => void }) {
    const [ name, setName ] = useState<string>("");
    const [ value, setValue ] = useState<string>("");
    const [ censored, setCensored ] = useState<boolean>(false);
    const [ error, setError ] = useState<Error | undefined>();

    const addVariable = async () => {
        if (!props.project) {
            return;
        }
        try {
            await getGitpodService().server.setProjectEnvironmentVariable(props.project.id, name, value, censored);
            props.onClose();
        } catch (err) {
            setError(err);
        }
    }

    return <Modal visible={true} onClose={props.onClose} onEnter={() => { addVariable(); return false; }}>
        <h3 className="mb-4">Add Variable</h3>
        <div className="border-t border-b border-gray-200 dark:border-gray-800 -mx-6 px-6 py-4 flex flex-col">
            {error && <div className="bg-gitpod-kumquat-light rounded-md p-3 text-gitpod-red text-sm mb-2">
                {error}
            </div>}
            <div>
                <h4>Name</h4>
                <input autoFocus className="w-full" type="text" name="name" value={name} onChange={e => setName(e.target.value)} />
            </div>
            <div className="mt-4">
                <h4>Value</h4>
                <input className="w-full" type="text" name="value" value={value} onChange={e => setValue(e.target.value)} />
            </div>
            <div className="mt-4">
                <CheckBox title="Visible in Prebuilds?" desc="All Project Variables are visible in Prebuilds" checked={true} disabled={true} />
            </div>
            <div className="mt-4">
                <CheckBox title="Visible in Workspaces?" desc="Choose whether this Variable should be visible in Workspaces" checked={!censored} onChange={() => setCensored(!censored)} />
            </div>
            {censored && <div className="mt-4">
                <InfoBox><strong>Never log this value</strong>: Prebuild logs are always visible in Workspaces.</InfoBox>
            </div>}
        </div>
        <div className="flex justify-end mt-6">
            <button className="secondary" onClick={props.onClose}>Cancel</button>
            <button className="ml-2" onClick={addVariable}>Add Variable</button>
        </div>
    </Modal>;
}
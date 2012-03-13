#include "comp_factory.h"
#include "resources/res_factory.h"

#include <string.h>

#include <dlib/array.h>
#include <dlib/dstrings.h>
#include <dlib/hash.h>
#include <dlib/index_pool.h>
#include <dlib/log.h>
#include <gameobject/gameobject.h>
#include <vectormath/cpp/vectormath_aos.h>

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    struct FactoryComponent
    {
        FactoryResource*    m_Resource;
    };

    struct FactoryWorld
    {
        dmArray<FactoryComponent>   m_Components;
        dmIndexPool32               m_IndexPool;
        uint32_t                    m_TotalFactoryCount;
    };

    dmGameObject::CreateResult CompFactoryNewWorld(const dmGameObject::ComponentNewWorldParams& params)
    {
        FactoryWorld* fw = new FactoryWorld();
        const uint32_t max_component_count = 64;
        fw->m_Components.SetCapacity(max_component_count); // TODO: Tweakable!
        fw->m_Components.SetSize(max_component_count);
        fw->m_IndexPool.SetCapacity(max_component_count);
        memset(&fw->m_Components[0], 0, sizeof(FactoryComponent) * max_component_count);
        *params.m_World = fw;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompFactoryDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params)
    {
        delete (FactoryWorld*)params.m_World;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompFactoryCreate(const dmGameObject::ComponentCreateParams& params)
    {
        FactoryWorld* fw = (FactoryWorld*)params.m_World;
        if (fw->m_IndexPool.Remaining() > 0)
        {
            uint32_t index = fw->m_IndexPool.Pop();
            FactoryComponent* fc = &fw->m_Components[index];
            fc->m_Resource = (FactoryResource*) params.m_Resource;
            *params.m_UserData = (uintptr_t) fc;

            return dmGameObject::CREATE_RESULT_OK;
        }
        else
        {
            dmLogError("Can not create more factory components since the buffer is full (%d).", fw->m_Components.Size());
            return dmGameObject::CREATE_RESULT_UNKNOWN_ERROR;
        }
    }

    dmGameObject::CreateResult CompFactoryDestroy(const dmGameObject::ComponentDestroyParams& params)
    {
        FactoryWorld* fw = (FactoryWorld*)params.m_World;
        FactoryComponent* fc = (FactoryComponent*)*params.m_UserData;
        uint32_t index = fc - &fw->m_Components[0];
        fc->m_Resource = 0x0;
        fw->m_IndexPool.Push(index);
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompFactoryOnMessage(const dmGameObject::ComponentOnMessageParams& params)
    {
        if (params.m_Message->m_Descriptor == (uintptr_t)dmGameSystemDDF::Create::m_DDFDescriptor)
        {
            dmGameObject::HInstance instance = params.m_Instance;
            dmGameObject::HCollection collection = dmGameObject::GetCollection(instance);
            dmMessage::Message* message = params.m_Message;
            if ((dmDDF::Descriptor*)params.m_Message->m_Descriptor == dmGameSystemDDF::Create::m_DDFDescriptor)
            {
                // redispatch messages on the component socket to the frame socket
                dmMessage::URL receiver = message->m_Receiver;
                if (receiver.m_Socket == dmGameObject::GetMessageSocket(collection))
                {
                    receiver.m_Socket = dmGameObject::GetFrameMessageSocket(collection);
                    dmMessage::Post(&message->m_Sender, &receiver, message->m_Id, message->m_UserData, message->m_Descriptor, (const void*)message->m_Data, message->m_DataSize);
                }
                else
                {
                    dmGameSystemDDF::Create* create = (dmGameSystemDDF::Create*) params.m_Message->m_Data;
                    uint32_t msg_size = sizeof(dmGameSystemDDF::Create);
                    uint32_t table_buffer_size = message->m_DataSize - msg_size;
                    uint8_t* table_buffer = 0x0;
                    if (table_buffer_size > 0)
                    {
                        table_buffer = (uint8_t*)(((uintptr_t)create) + msg_size);
                    }
                    FactoryComponent* fc = (FactoryComponent*) *params.m_UserData;
                    dmhash_t id = create->m_Id;
                    if (id == 0)
                    {
                        id = dmGameObject::GenerateUniqueInstanceId(collection);
                    }
                    dmGameObject::Spawn(collection, fc->m_Resource->m_FactoryDesc->m_Prototype, id, table_buffer, create->m_Position, create->m_Rotation);
                }
            }
            else
            {
                dmLogError("Invalid DDF-type for create message");
                return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
            }
        }
        return dmGameObject::UPDATE_RESULT_OK;
    }
}
